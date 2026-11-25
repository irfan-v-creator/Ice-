package com.example.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

data class DbIntegrityReport(
  val integrityOk: Boolean,
  val integrityMessage: String,
  val foreignKeyViolationCount: Int
) {
  val isHealthy: Boolean
    get() = integrityOk && foreignKeyViolationCount == 0
}

/**
 * Provides deterministic per-UID database naming, SQLite integrity & FK verification,
 * and safe WAL checkpointing/closure without aborting active transactions.
 */
object DatabaseIntegrityManager {

  fun uidToSafeHash(uid: String): String {
    val trimmed = uid.trim()
    require(trimmed.isNotEmpty()) { "Authenticated UID must not be blank when deriving database name." }
    val digest = MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString("") { "%02x".format(it) }
  }

  fun uidBusinessDbName(uid: String): String = "icetrack_db_${uidToSafeHash(uid)}.db"

  fun uidSyncDbName(uid: String): String = "icetrack_sync_${uidToSafeHash(uid)}.db"

  fun verifyIntegrity(db: SupportSQLiteDatabase): DbIntegrityReport {
    val integrityMsg = db.query("PRAGMA integrity_check").use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) ?: "unknown" else "empty"
    }
    val integrityOk = integrityMsg.equals("ok", ignoreCase = true)

    var fkViolations = 0
    db.query("PRAGMA foreign_key_check").use { cursor ->
      while (cursor.moveToNext()) {
        fkViolations++
      }
    }

    return DbIntegrityReport(
      integrityOk = integrityOk,
      integrityMessage = integrityMsg,
      foreignKeyViolationCount = fkViolations
    )
  }

  fun closeSafely(db: RoomDatabase): Result<Unit> {
    return try {
      if (!db.isOpen) {
        return Result.success(Unit)
      }
      if (db.inTransaction()) {
        return Result.failure(
          IllegalStateException("Cannot close database while an active SQLite transaction is running.")
        )
      }
      val writable = db.openHelper.writableDatabase
      if (writable.inTransaction()) {
        return Result.failure(
          IllegalStateException("Cannot checkpoint WAL while writable database is in transaction.")
        )
      }
      writable.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
        if (cursor.moveToFirst()) {
          val busy = cursor.getInt(0)
          if (busy != 0) {
            return Result.failure(
              IllegalStateException("SQLite WAL checkpoint reported busy readers/writers (busy=$busy).")
            )
          }
        }
      }
      db.close()
      Result.success(Unit)
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }
}
