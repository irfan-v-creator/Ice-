package com.example.session

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.DatabaseIntegrityManager
import com.example.data.local.DbIntegrityReport
import com.example.data.local.IceTrackDatabase
import com.example.data.sync.IceTrackSyncDatabase
import com.example.service.BackupRestoreService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LegacyOwnershipEvidence {
  MATCHED_SYNC_METADATA,
  UNASSIGNED_OFFLINE_LEGACY
}

data class LegacyDataSummary(
  val productsCount: Int = 0,
  val suppliersCount: Int = 0,
  val customersCount: Int = 0,
  val purchaseInvoicesCount: Int = 0,
  val salesInvoicesCount: Int = 0,
  val subledgerEntriesCount: Int = 0,
  val expensesCount: Int = 0,
  val auditLogsCount: Int = 0,
  val settingsCount: Int = 0,
  val batchesCount: Int = 0
) {
  val totalBusinessRows: Int
    get() = productsCount + suppliersCount + customersCount +
      purchaseInvoicesCount + salesInvoicesCount + subledgerEntriesCount +
      expensesCount + auditLogsCount + batchesCount
}

sealed interface LegacyMigrationAssessment {
  data object NoLegacyData : LegacyMigrationAssessment
  data object AlreadyMigratedToCurrentUser : LegacyMigrationAssessment
  data object ClaimedByAnotherAccount : LegacyMigrationAssessment
  data class OwnershipMismatchBlocked(
    val recordedOwnerUids: Set<String>,
    val summary: LegacyDataSummary,
    val reason: String
  ) : LegacyMigrationAssessment
  data class RequiresOwnerConfirmation(
    val evidence: LegacyOwnershipEvidence,
    val summary: LegacyDataSummary,
    val legacyIntegrityReport: DbIntegrityReport
  ) : LegacyMigrationAssessment
}

data class LegacyMigrationExecutionResult(
  val success: Boolean,
  val backupFilePath: String? = null,
  val importedSummary: LegacyDataSummary? = null,
  val errorMessage: String? = null
)

/**
 * Handles safe inspection and owner-authorized migration of pre-Phase-1 global database
 * ("icetrack_database.db") into the verified owner's isolated per-UID database.
 *
 * Guarantees:
 * - Never deletes or overwrites the legacy global database.
 * - Never guesses ownership or auto-assigns legacy data to the first user who signs in.
 * - Creates a validated recoverable V5 JSON backup before writing any migrated data.
 * - Validates record counts and SQLite integrity/FK constraints post-import.
 * - Prevents duplicate imports and cross-account contamination.
 */
class LegacyDatabaseMigrationManager(
  private val context: Context,
  private val markerStore: SignOutMarkerStore
) {

  private fun legacyBusinessDbFile(): File =
    context.getDatabasePath(IceTrackDatabase.LEGACY_GLOBAL_DB_NAME)

  private fun legacySyncDbFile(): File =
    context.getDatabasePath(IceTrackSyncDatabase.LEGACY_GLOBAL_SYNC_DB_NAME)

  suspend fun exportRecoverableLegacyBackupJson(): Result<String> = withContext(Dispatchers.IO) {
    val legacyFile = legacyBusinessDbFile()
    if (!legacyFile.exists()) {
      return@withContext Result.failure(IllegalStateException("Legacy global database does not exist."))
    }
    var legacyDb: IceTrackDatabase? = null
    try {
      legacyDb = IceTrackDatabase.openMigratedLegacyDatabase(context)
      val json = BackupRestoreService.exportDatabaseFromDatabase(legacyDb)
      val validation = BackupRestoreService.validateBackupJson(json)
      if (!validation.isValid) {
        Result.failure(IllegalStateException(validation.errorMessage ?: "Legacy JSON export failed validation."))
      } else {
        Result.success(json)
      }
    } catch (e: Throwable) {
      Result.failure(e)
    } finally {
      legacyDb?.let { DatabaseIntegrityManager.closeSafely(it) }
    }
  }

  suspend fun assessForUser(uid: String): LegacyMigrationAssessment = withContext(Dispatchers.IO) {
    val uidHash = DatabaseIntegrityManager.uidToSafeHash(uid)
    val claimedHash = markerStore.getLegacyClaimedOwnerUidHash()
    if (claimedHash != null && claimedHash != uidHash) {
      return@withContext LegacyMigrationAssessment.ClaimedByAnotherAccount
    }
    if (claimedHash == uidHash) {
      return@withContext LegacyMigrationAssessment.AlreadyMigratedToCurrentUser
    }

    val legacyFile = legacyBusinessDbFile()
    if (!legacyFile.exists() || legacyFile.length() == 0L) {
      return@withContext LegacyMigrationAssessment.NoLegacyData
    }

    if (markerStore.isLegacyMigrationDeferredForUid(uidHash)) {
      return@withContext LegacyMigrationAssessment.NoLegacyData
    }

    var legacyDb: IceTrackDatabase? = null
    try {
      legacyDb = IceTrackDatabase.openMigratedLegacyDatabase(context)
      val summary = collectSummary(legacyDb)
      if (summary.totalBusinessRows == 0 && summary.settingsCount == 0) {
        return@withContext LegacyMigrationAssessment.NoLegacyData
      }

      val integrityReport = DatabaseIntegrityManager.verifyIntegrity(legacyDb.openHelper.writableDatabase)
      val recordedSyncOwners = inspectLegacySyncOwners()

      if (recordedSyncOwners.size > 1) {
        return@withContext LegacyMigrationAssessment.OwnershipMismatchBlocked(
          recordedOwnerUids = recordedSyncOwners,
          summary = summary,
          reason = "Legacy sync metadata contains records from multiple accounts (${recordedSyncOwners.size}). Automatic or single-account import is blocked to prevent cross-account mixing."
        )
      }

      if (recordedSyncOwners.size == 1 && recordedSyncOwners.first() != uid) {
        val otherUidPreview = recordedSyncOwners.first().take(8)
        return@withContext LegacyMigrationAssessment.OwnershipMismatchBlocked(
          recordedOwnerUids = recordedSyncOwners,
          summary = summary,
          reason = "Existing offline database sync metadata belongs to another Google account ($otherUidPreview…). Sign in with that owner account instead."
        )
      }

      val evidence = if (recordedSyncOwners.size == 1 && recordedSyncOwners.first() == uid) {
        LegacyOwnershipEvidence.MATCHED_SYNC_METADATA
      } else {
        LegacyOwnershipEvidence.UNASSIGNED_OFFLINE_LEGACY
      }

      LegacyMigrationAssessment.RequiresOwnerConfirmation(
        evidence = evidence,
        summary = summary,
        legacyIntegrityReport = integrityReport
      )
    } finally {
      legacyDb?.let { DatabaseIntegrityManager.closeSafely(it) }
    }
  }

  suspend fun deferLegacyMigrationForUser(uid: String): Boolean = withContext(Dispatchers.IO) {
    val uidHash = DatabaseIntegrityManager.uidToSafeHash(uid)
    markerStore.setLegacyMigrationDeferredForUid(uidHash, true)
  }

  suspend fun migrateLegacyToUser(uid: String): LegacyMigrationExecutionResult = withContext(Dispatchers.IO) {
    val uidHash = DatabaseIntegrityManager.uidToSafeHash(uid)
    val claimedHash = markerStore.getLegacyClaimedOwnerUidHash()
    if (claimedHash != null) {
      return@withContext LegacyMigrationExecutionResult(
        success = false,
        errorMessage = if (claimedHash == uidHash) {
          "Legacy database has already been migrated for this account (duplicate import prevented)."
        } else {
          "Legacy database was already claimed by another account."
        }
      )
    }

    val legacyFile = legacyBusinessDbFile()
    if (!legacyFile.exists()) {
      return@withContext LegacyMigrationExecutionResult(
        success = false,
        errorMessage = "Legacy database file not found."
      )
    }

    var legacyDb: IceTrackDatabase? = null
    var destDb: IceTrackDatabase? = null
    var destSyncDb: IceTrackSyncDatabase? = null
    try {
      legacyDb = IceTrackDatabase.openMigratedLegacyDatabase(context)
      val sourceSummary = collectSummary(legacyDb)
      val sourceIntegrity = DatabaseIntegrityManager.verifyIntegrity(legacyDb.openHelper.writableDatabase)
      if (!sourceIntegrity.isHealthy) {
        return@withContext LegacyMigrationExecutionResult(
          success = false,
          errorMessage = "Legacy database failed SQLite integrity/FK check: ${sourceIntegrity.integrityMessage} (FK violations: ${sourceIntegrity.foreignKeyViolationCount})."
        )
      }

      val recordedSyncOwners = inspectLegacySyncOwners()
      if (recordedSyncOwners.isNotEmpty() && (recordedSyncOwners.size > 1 || recordedSyncOwners.first() != uid)) {
        return@withContext LegacyMigrationExecutionResult(
          success = false,
          errorMessage = "Legacy database ownership mismatch with sync metadata. Migration aborted."
        )
      }

      // Prevent duplicate import into an already populated per-UID database
      destDb = IceTrackDatabase.openForUid(context, uid)
      val destExistingSummary = collectSummary(destDb)
      if (destExistingSummary.totalBusinessRows > 0) {
        return@withContext LegacyMigrationExecutionResult(
          success = false,
          errorMessage = "Account database already contains ${destExistingSummary.totalBusinessRows} business records. Migration blocked to prevent overwriting or duplicating account data."
        )
      }

      // Create validated recoverable V5 JSON backup before migrating
      val exportJson = BackupRestoreService.exportDatabaseFromDatabase(legacyDb)
      val validation = BackupRestoreService.validateBackupJson(exportJson)
      if (!validation.isValid) {
        return@withContext LegacyMigrationExecutionResult(
          success = false,
          errorMessage = "Pre-migration backup validation failed: ${validation.errorMessage}"
        )
      }

      val backupDir = File(context.filesDir, "legacy_backups").apply { mkdirs() }
      val backupFile = File(backupDir, "legacy_pre_migration_${uidHash}_${System.currentTimeMillis()}.json")
      backupFile.writeText(exportJson, Charsets.UTF_8)

      // Transactionally restore into destination per-UID database
      BackupRestoreService.restoreDatabaseFromJson(
        db = destDb,
        jsonString = exportJson,
        reason = "Verified legacy owner migration ($uidHash)"
      )

      // Verify exact record counts and SQLite integrity after migration
      val migratedSummary = collectSummary(destDb)
      val countMismatches = verifyCountsMatch(sourceSummary, migratedSummary)
      if (countMismatches.isNotEmpty()) {
        return@withContext LegacyMigrationExecutionResult(
          success = false,
          backupFilePath = backupFile.absolutePath,
          errorMessage = "Post-migration count verification failed: ${countMismatches.joinToString("; ")}"
        )
      }

      val destIntegrity = DatabaseIntegrityManager.verifyIntegrity(destDb.openHelper.writableDatabase)
      if (!destIntegrity.isHealthy) {
        return@withContext LegacyMigrationExecutionResult(
          success = false,
          backupFilePath = backupFile.absolutePath,
          errorMessage = "Destination database failed post-migration integrity check."
        )
      }

      // Migrate matching sync queue records for this UID without modifying legacy sync DB
      migrateMatchingSyncRecords(uid) { targetSync ->
        destSyncDb = targetSync
      }

      val claimPersisted = markerStore.persistLegacyClaimedOwnerSync(
        uidHash = uidHash,
        backupFilePath = backupFile.absolutePath
      )
      if (!claimPersisted) {
        return@withContext LegacyMigrationExecutionResult(
          success = false,
          backupFilePath = backupFile.absolutePath,
          errorMessage = "Failed to persist legacy migration claim marker."
        )
      }
      markerStore.setLegacyMigrationDeferredForUid(uidHash, false)

      LegacyMigrationExecutionResult(
        success = true,
        backupFilePath = backupFile.absolutePath,
        importedSummary = migratedSummary
      )
    } catch (e: Throwable) {
      LegacyMigrationExecutionResult(
        success = false,
        errorMessage = "Legacy migration failed: ${e.message ?: e::class.java.simpleName}"
      )
    } finally {
      legacyDb?.let { DatabaseIntegrityManager.closeSafely(it) }
      // Close destDb & destSyncDb cleanly so SessionScopeManager opens fresh active session instances
      if (destDb != null) {
        IceTrackDatabase.closeForUid(uid)
      }
      if (destSyncDb != null) {
        IceTrackSyncDatabase.closeForUid(uid)
      }
    }
  }

  private suspend fun migrateMatchingSyncRecords(
    uid: String,
    captureDest: (IceTrackSyncDatabase) -> Unit
  ) {
    val syncFile = legacySyncDbFile()
    if (!syncFile.exists()) return
    var legacySync: IceTrackSyncDatabase? = null
    try {
      legacySync = IceTrackSyncDatabase.openLegacySyncDatabase(context)
      val dao = legacySync.syncStateDao()
      val records = dao.getAllForUserSync(uid)
      val meta = dao.getWorkspaceMetaSync(uid)
      if (records.isNotEmpty() || meta != null) {
        val destSync = IceTrackSyncDatabase.openForUid(context, uid)
        captureDest(destSync)
        destSync.withTransaction {
          if (records.isNotEmpty()) {
            destSync.syncStateDao().upsertAllRecords(records)
          }
          if (meta != null) {
            destSync.syncStateDao().upsertWorkspaceMeta(meta)
          }
        }
      }
    } finally {
      legacySync?.let { DatabaseIntegrityManager.closeSafely(it) }
    }
  }

  private suspend fun inspectLegacySyncOwners(): Set<String> {
    val syncFile = legacySyncDbFile()
    if (!syncFile.exists() || syncFile.length() == 0L) return emptySet()
    var legacySync: IceTrackSyncDatabase? = null
    return try {
      legacySync = IceTrackSyncDatabase.openLegacySyncDatabase(context)
      val owners = mutableSetOf<String>()
      val readable = legacySync.openHelper.readableDatabase
      readable.query("SELECT DISTINCT ownerUid FROM sync_record_states").use { c ->
        while (c.moveToNext()) {
          c.getString(0)?.takeIf { it.isNotBlank() }?.let { owners.add(it) }
        }
      }
      readable.query("SELECT DISTINCT ownerUid FROM sync_workspace_meta").use { c ->
        while (c.moveToNext()) {
          c.getString(0)?.takeIf { it.isNotBlank() }?.let { owners.add(it) }
        }
      }
      owners
    } catch (_: Throwable) {
      emptySet()
    } finally {
      legacySync?.let { DatabaseIntegrityManager.closeSafely(it) }
    }
  }

  internal suspend fun collectSummary(db: IceTrackDatabase): LegacyDataSummary = LegacyDataSummary(
    productsCount = db.productDao().getAllIncludingArchivedSync().size,
    suppliersCount = db.supplierDao().getAllIncludingArchivedSync().size,
    customersCount = db.customerDao().getAllIncludingArchivedSync().size,
    purchaseInvoicesCount = db.purchaseInvoiceDao().getAllSync().size,
    salesInvoicesCount = db.salesInvoiceDao().getAllSync().size,
    subledgerEntriesCount = db.subledgerDao().getAllEntriesSync().size,
    expensesCount = db.expenseDao().getAllIncludingDeletedSync().size,
    auditLogsCount = db.financialAuditLogDao().getAllSync().size,
    settingsCount = db.appSettingDao().getAllSync().size,
    batchesCount = db.productBatchDao().getAllBatchesSync().size
  )

  private fun verifyCountsMatch(src: LegacyDataSummary, dst: LegacyDataSummary): List<String> {
    val errs = mutableListOf<String>()
    if (src.productsCount != dst.productsCount) errs.add("products ${src.productsCount}!=${dst.productsCount}")
    if (src.suppliersCount != dst.suppliersCount) errs.add("suppliers ${src.suppliersCount}!=${dst.suppliersCount}")
    if (src.customersCount != dst.customersCount) errs.add("customers ${src.customersCount}!=${dst.customersCount}")
    if (src.purchaseInvoicesCount != dst.purchaseInvoicesCount) errs.add("purchases ${src.purchaseInvoicesCount}!=${dst.purchaseInvoicesCount}")
    if (src.salesInvoicesCount != dst.salesInvoicesCount) errs.add("sales ${src.salesInvoicesCount}!=${dst.salesInvoicesCount}")
    if (src.subledgerEntriesCount != dst.subledgerEntriesCount) errs.add("subledger ${src.subledgerEntriesCount}!=${dst.subledgerEntriesCount}")
    if (src.expensesCount != dst.expensesCount) errs.add("expenses ${src.expensesCount}!=${dst.expensesCount}")
    if (src.batchesCount != dst.batchesCount) errs.add("batches ${src.batchesCount}!=${dst.batchesCount}")
    // Restore writes +1 BACKUP_RESTORE audit entry
    if (dst.auditLogsCount < src.auditLogsCount + 1) errs.add("auditLogs ${src.auditLogsCount}+1>${dst.auditLogsCount}")
    return errs
  }
}
