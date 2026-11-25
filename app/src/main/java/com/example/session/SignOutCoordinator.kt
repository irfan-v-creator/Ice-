package com.example.session

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.example.R
import com.example.data.local.IceTrackDatabase
import com.example.data.sync.IceTrackSyncDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AuthUserDescriptor(
  val uid: String,
  val email: String,
  val displayName: String
)

fun FirebaseUser.toDescriptor(): AuthUserDescriptor = AuthUserDescriptor(
  uid = uid,
  email = email ?: "",
  displayName = displayName ?: ""
)

enum class SignOutFlowState {
  IDLE,
  SIGNOUT_CHECKING,
  MUTATIONS_FROZEN,
  PENDING_WRITES_BLOCKED,
  WAITING_FOR_SAFE_SHUTDOWN,
  DISCARD_AND_SIGNOUT_PENDING,
  SIGNOUT_FAILED,
  SIGNOUT_COMPLETED
}

data class SignOutUiPrompt(
  val uid: String,
  val uidHash: String,
  val state: SignOutFlowState,
  val pendingLocalSyncCount: Int = 0,
  val hasPendingCloudWrites: Boolean = false,
  val statusMessage: String = "",
  val errorMessage: String? = null
)

interface AuthSessionGateway {
  val currentUser: AuthUserDescriptor?
  fun authStateFlow(): Flow<AuthUserDescriptor?>
  suspend fun clearCredentialState(): Result<Unit>
  suspend fun signOutFirebase(): Result<Unit>
}

interface FirestoreLifecycleGateway {
  fun hasActiveInMemoryInstance(): Boolean
  suspend fun waitForPendingWrites(timeoutMs: Long): Result<Boolean>
  suspend fun terminateAndClearPersistence(): Result<Unit>
}

interface DatabaseLifecycleGateway {
  fun openBusinessDb(uid: String): IceTrackDatabase
  fun openSyncDb(uid: String): IceTrackSyncDatabase
  fun closeBusinessDb(uid: String): Result<Unit>
  fun closeSyncDb(uid: String): Result<Unit>
}

class DefaultAuthSessionGateway(
  context: Context,
  private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : AuthSessionGateway {
  private val appContext = context.applicationContext

  override val currentUser: AuthUserDescriptor?
    get() = auth.currentUser?.toDescriptor()

  override fun authStateFlow(): Flow<AuthUserDescriptor?> = callbackFlow {
    trySend(auth.currentUser?.toDescriptor())
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
      trySend(firebaseAuth.currentUser?.toDescriptor())
    }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
  }

  override suspend fun clearCredentialState(): Result<Unit> {
    return try {
      CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }

  override suspend fun signOutFirebase(): Result<Unit> {
    return try {
      auth.signOut()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }
}

class DefaultFirestoreLifecycleGateway(
  context: Context
) : FirestoreLifecycleGateway {
  private val appContext = context.applicationContext

  override fun hasActiveInMemoryInstance(): Boolean = synchronized(instanceTrackerLock) {
    activeFirestoreCreated
  }

  override suspend fun waitForPendingWrites(timeoutMs: Long): Result<Boolean> {
    if (!hasActiveInMemoryInstance()) {
      return Result.success(true)
    }
    return try {
      val dbId = appContext.getString(R.string.firestore_database_id)
      val instance = FirebaseFirestore.getInstance(dbId)
      val completed = withTimeoutOrNull(timeoutMs) {
        instance.waitForPendingWrites().await()
        true
      } ?: false
      Result.success(completed)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }

  override suspend fun terminateAndClearPersistence(): Result<Unit> {
    return try {
      val dbId = appContext.getString(R.string.firestore_database_id)
      val instance = FirebaseFirestore.getInstance(dbId)
      // Strict mandatory order: terminate() first, then clearPersistence()
      instance.terminate().await()
      markFirestoreInstanceTerminated()
      instance.clearPersistence().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }

  companion object {
    private val instanceTrackerLock = Any()

    @Volatile
    private var activeFirestoreCreated: Boolean = false

    fun markFirestoreInstanceCreated() {
      synchronized(instanceTrackerLock) {
        activeFirestoreCreated = true
      }
    }

    fun markFirestoreInstanceTerminated() {
      synchronized(instanceTrackerLock) {
        activeFirestoreCreated = false
      }
    }
  }
}

class DefaultDatabaseLifecycleGateway(
  context: Context
) : DatabaseLifecycleGateway {
  private val appContext = context.applicationContext

  override fun openBusinessDb(uid: String): IceTrackDatabase =
    IceTrackDatabase.openForUid(appContext, uid)

  override fun openSyncDb(uid: String): IceTrackSyncDatabase =
    IceTrackSyncDatabase.openForUid(appContext, uid)

  override fun closeBusinessDb(uid: String): Result<Unit> =
    IceTrackDatabase.closeForUid(uid)

  override fun closeSyncDb(uid: String): Result<Unit> =
    IceTrackSyncDatabase.closeForUid(uid)
}

sealed interface SignOutStepOutcome {
  data object Completed : SignOutStepOutcome
  data class AwaitingUserDecision(val prompt: SignOutUiPrompt) : SignOutStepOutcome
  data class Failed(val prompt: SignOutUiPrompt) : SignOutStepOutcome
}

/**
 * Executes the Two-Path Safe Sign-Out State Machine:
 * - Path A (Normal Sign-Out): freezes mutations, drains active transactions/writes, checks
 *   Firestore acknowledgement (if Firestore was active), preserves local Room + local sync queue,
 *   detaches listeners, clears per-UID ViewModels, WAL-checkpoints & closes DBs, clears
 *   CredentialManager, signs out Firebase.
 * - Path B (Explicit Discard & Sign-Out): synchronously persists DISCARD_AND_SIGNOUT_PENDING,
 *   marks reconciliationRequired, freezes/drains/unmounts/closes DBs safely while preserving local
 *   data, persists CLEANUP_REQUIRED, runs Firestore terminate() -> clearPersistence(), clears
 *   credentials, signs out.
 * - Startup crash recovery handles any interrupted DISCARD_AND_SIGNOUT_PENDING / CLEANUP_REQUIRED
 *   marker BEFORE opening any business database.
 */
class SignOutCoordinator(
  private val markerStore: SignOutMarkerStore,
  private val authGateway: AuthSessionGateway,
  private val firestoreGateway: FirestoreLifecycleGateway,
  private val databaseGateway: DatabaseLifecycleGateway,
  private val drainTimeoutMs: Long = 5_000L,
  private val firestoreAckTimeoutMs: Long = 4_000L
) {

  suspend fun runStartupRecoveryIfNeeded(): Result<Boolean> = withContext(NonCancellable) {
    val marker = markerStore.readPendingMarker() ?: return@withContext Result.success(false)
    if (marker.uidHash.isNotBlank()) {
      val recSaved = markerStore.persistReconciliationRequiredSync(marker.uidHash, true)
      if (!recSaved) {
        return@withContext Result.failure(
          IllegalStateException("Failed to persist reconciliationRequired marker during startup recovery.")
        )
      }
    }

    val currentUser = authGateway.currentUser
    if (currentUser != null) {
      val bizClose = databaseGateway.closeBusinessDb(currentUser.uid)
      if (bizClose.isFailure) return@withContext Result.failure(bizClose.exceptionOrNull()!!)
      val syncClose = databaseGateway.closeSyncDb(currentUser.uid)
      if (syncClose.isFailure) return@withContext Result.failure(syncClose.exceptionOrNull()!!)
    }

    if (marker.phase == SignOutMarkerPhase.DISCARD_AND_SIGNOUT_PENDING && marker.uidHash.isNotBlank()) {
      markerStore.persistCleanupRequiredSync(marker.uidHash)
    }

    val fsClear = firestoreGateway.terminateAndClearPersistence()
    if (fsClear.isFailure) {
      return@withContext Result.failure(
        fsClear.exceptionOrNull()
          ?: IllegalStateException("Failed to clear pending Firestore persistence during recovery.")
      )
    }

    val credClear = authGateway.clearCredentialState()
    if (credClear.isFailure) {
      return@withContext Result.failure(
        credClear.exceptionOrNull()
          ?: IllegalStateException("Failed to clear credential state during recovery.")
      )
    }

    val signOutRes = authGateway.signOutFirebase()
    if (signOutRes.isFailure) {
      return@withContext Result.failure(
        signOutRes.exceptionOrNull()
          ?: IllegalStateException("Failed to complete Firebase sign-out during recovery.")
      )
    }

    if (!markerStore.clearSignOutMarkerSync()) {
      return@withContext Result.failure(
        IllegalStateException("Failed to clear sign-out recovery marker after cleanup.")
      )
    }

    Result.success(true)
  }

  suspend fun beginNormalSignOut(
    session: ActiveUserSession,
    onStateChanged: (SignOutUiPrompt) -> Unit,
    onUnmountActiveSession: suspend () -> Unit
  ): SignOutStepOutcome = withContext(NonCancellable) {
    onStateChanged(
      SignOutUiPrompt(
        uid = session.uid,
        uidHash = session.uidHash,
        state = SignOutFlowState.SIGNOUT_CHECKING,
        statusMessage = "Checking active database operations and pending sync records..."
      )
    )

    onStateChanged(
      SignOutUiPrompt(
        uid = session.uid,
        uidHash = session.uidHash,
        state = SignOutFlowState.MUTATIONS_FROZEN,
        statusMessage = "Freezing new edits while finishing in-flight transactions..."
      )
    )

    val drainResult = session.mutationGate.freezeAndDrain(
      isDbInTransaction = { session.database.inTransaction() || session.syncDatabase.inTransaction() },
      timeoutMs = drainTimeoutMs
    )
    if (drainResult is DrainResult.TimedOut) {
      session.mutationGate.unfreezeIfSafe()
      val failPrompt = SignOutUiPrompt(
        uid = session.uid,
        uidHash = session.uidHash,
        state = SignOutFlowState.SIGNOUT_FAILED,
        errorMessage = "Active transaction did not finish within ${drainTimeoutMs}ms (active=${drainResult.remainingActiveMutations}, inTx=${drainResult.dbInTransaction}). Sign-out cancelled safely so no data was lost."
      )
      onStateChanged(failPrompt)
      return@withContext SignOutStepOutcome.Failed(failPrompt)
    }

    val pendingLocalCount = countLocalPendingRecords(session)

    if (firestoreGateway.hasActiveInMemoryInstance()) {
      val waitRes = firestoreGateway.waitForPendingWrites(firestoreAckTimeoutMs)
      if (waitRes.isFailure || waitRes.getOrNull() != true) {
        val reason = waitRes.exceptionOrNull()?.message
          ?: "Pending Firestore writes were not acknowledged while offline or within timeout."
        val blockedPrompt = SignOutUiPrompt(
          uid = session.uid,
          uidHash = session.uidHash,
          state = SignOutFlowState.PENDING_WRITES_BLOCKED,
          pendingLocalSyncCount = pendingLocalCount,
          hasPendingCloudWrites = true,
          statusMessage = "Pending cloud writes are not yet acknowledged. Your $pendingLocalCount local records remain saved in Room.",
          errorMessage = reason
        )
        onStateChanged(blockedPrompt)
        return@withContext SignOutStepOutcome.AwaitingUserDecision(blockedPrompt)
      }
    }

    performSafeNormalShutdown(
      session = session,
      pendingLocalCount = pendingLocalCount,
      onStateChanged = onStateChanged,
      onUnmountActiveSession = onUnmountActiveSession
    )
  }

  suspend fun cancelPendingSignOut(session: ActiveUserSession) {
    session.mutationGate.unfreezeIfSafe()
  }

  suspend fun performSafeNormalShutdown(
    session: ActiveUserSession,
    pendingLocalCount: Int,
    onStateChanged: (SignOutUiPrompt) -> Unit,
    onUnmountActiveSession: suspend () -> Unit
  ): SignOutStepOutcome = withContext(NonCancellable) {
    onStateChanged(
      SignOutUiPrompt(
        uid = session.uid,
        uidHash = session.uidHash,
        state = SignOutFlowState.WAITING_FOR_SAFE_SHUTDOWN,
        pendingLocalSyncCount = pendingLocalCount,
        statusMessage = "Safely closing databases and clearing session caches..."
      )
    )

    // Unmount Compose/UI observers BEFORE closing Room databases
    onUnmountActiveSession()

    val detachRes = session.cloudSyncRepo.detachAllListenersStrict()
    if (detachRes.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount,
        "Failed to detach Firestore listeners: ${detachRes.exceptionOrNull()?.message}",
        onStateChanged
      )
    }

    session.cancelJobsAndClearViewModels()

    val bizClose = databaseGateway.closeBusinessDb(session.uid)
    if (bizClose.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount,
        "Failed to safely close business database: ${bizClose.exceptionOrNull()?.message}",
        onStateChanged
      )
    }

    val syncClose = databaseGateway.closeSyncDb(session.uid)
    if (syncClose.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount,
        "Failed to safely close sync database: ${syncClose.exceptionOrNull()?.message}",
        onStateChanged
      )
    }

    val credRes = authGateway.clearCredentialState()
    if (credRes.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount,
        "Failed to clear Credential Manager state: ${credRes.exceptionOrNull()?.message}",
        onStateChanged
      )
    }

    val signOutRes = authGateway.signOutFirebase()
    if (signOutRes.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount,
        "Firebase sign-out failed: ${signOutRes.exceptionOrNull()?.message}",
        onStateChanged
      )
    }

    onStateChanged(
      SignOutUiPrompt(
        uid = session.uid,
        uidHash = session.uidHash,
        state = SignOutFlowState.SIGNOUT_COMPLETED,
        statusMessage = "Signed out safely."
      )
    )
    SignOutStepOutcome.Completed
  }

  /**
   * Explicit user-authorized Discard Pending Firestore Writes & Sign Out flow.
   * Preserves local Room business database & local sync_record_states queue,
   * marks reconciliationRequired, and follows strict synchronous SharedPreferences.commit() order.
   */
  suspend fun executeExplicitDiscardAndSignOut(
    session: ActiveUserSession,
    onStateChanged: (SignOutUiPrompt) -> Unit,
    onUnmountActiveSession: suspend () -> Unit
  ): SignOutStepOutcome = withContext(NonCancellable) {
    // 1. Synchronously persist DISCARD_AND_SIGNOUT_PENDING with UID hash BEFORE closing/tearing down
    val pendingPersisted = markerStore.persistDiscardPendingSync(session.uidHash)
    if (!pendingPersisted) {
      session.mutationGate.unfreezeIfSafe()
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Could not persist durable DISCARD_AND_SIGNOUT_PENDING marker; aborting discard to protect data.",
        onStateChanged = onStateChanged
      )
    }

    val reconcileSaved = markerStore.persistReconciliationRequiredSync(session.uidHash, true)
    if (!reconcileSaved) {
      markerStore.clearSignOutMarkerSync()
      session.mutationGate.unfreezeIfSafe()
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Could not persist reconciliationRequired flag; aborting discard.",
        onStateChanged = onStateChanged
      )
    }

    onStateChanged(
      SignOutUiPrompt(
        uid = session.uid,
        uidHash = session.uidHash,
        state = SignOutFlowState.DISCARD_AND_SIGNOUT_PENDING,
        statusMessage = "Clearing pending cloud cache while keeping local databases intact..."
      )
    )

    // 2. Ensure mutations frozen & drained safely
    val drainRes = session.mutationGate.freezeAndDrain(
      isDbInTransaction = { session.database.inTransaction() || session.syncDatabase.inTransaction() },
      timeoutMs = drainTimeoutMs
    )
    if (drainRes is DrainResult.TimedOut) {
      // Recovery marker stays intact so startup recovery or retry handles it cleanly
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Database transaction still running; cannot safely close DB during discard.",
        onStateChanged = onStateChanged
      )
    }

    // 3. Unmount ActiveUserSession
    onUnmountActiveSession()

    // 4. Detach listeners, cancel/join session jobs, clear per-UID ViewModelStore
    val detachRes = session.cloudSyncRepo.detachAllListenersStrict()
    if (detachRes.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Listener detach failed during discard sign-out: ${detachRes.exceptionOrNull()?.message}",
        onStateChanged = onStateChanged
      )
    }
    session.cancelJobsAndClearViewModels()

    // 5. Close UID Business DB and Sync DB safely (Room data + local sync queue remain preserved)
    val bizClose = databaseGateway.closeBusinessDb(session.uid)
    if (bizClose.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Failed to close business database during discard: ${bizClose.exceptionOrNull()?.message}",
        onStateChanged = onStateChanged
      )
    }
    val syncClose = databaseGateway.closeSyncDb(session.uid)
    if (syncClose.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Failed to close sync database during discard: ${syncClose.exceptionOrNull()?.message}",
        onStateChanged = onStateChanged
      )
    }

    // 6. Synchronously persist CLEANUP_REQUIRED
    val cleanupMarkerSaved = markerStore.persistCleanupRequiredSync(session.uidHash)
    if (!cleanupMarkerSaved) {
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Failed to persist CLEANUP_REQUIRED recovery marker.",
        onStateChanged = onStateChanged
      )
    }

    // 7. Execute Firestore terminate() then clearPersistence()
    val fsClear = firestoreGateway.terminateAndClearPersistence()
    if (fsClear.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Firestore persistence cleanup failed: ${fsClear.exceptionOrNull()?.message}",
        onStateChanged = onStateChanged
      )
    }

    // 8. Clear CredentialManager & FirebaseAuth signOut
    val credRes = authGateway.clearCredentialState()
    if (credRes.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Credential cleanup failed: ${credRes.exceptionOrNull()?.message}",
        onStateChanged = onStateChanged
      )
    }

    val signOutRes = authGateway.signOutFirebase()
    if (signOutRes.isFailure) {
      return@withContext failShutdown(
        session,
        pendingLocalCount = 0,
        message = "Firebase sign-out failed: ${signOutRes.exceptionOrNull()?.message}",
        onStateChanged = onStateChanged
      )
    }

    markerStore.clearSignOutMarkerSync()
    onStateChanged(
      SignOutUiPrompt(
        uid = session.uid,
        uidHash = session.uidHash,
        state = SignOutFlowState.SIGNOUT_COMPLETED,
        statusMessage = "Pending cloud cache discarded safely; local records preserved."
      )
    )
    SignOutStepOutcome.Completed
  }

  private suspend fun countLocalPendingRecords(session: ActiveUserSession): Int {
    return runCatching {
      val dao = session.syncDatabase.syncStateDao()
      dao.countByStatusSync(session.uid, "PENDING_UPLOAD") +
        dao.countByStatusSync(session.uid, "FAILED") +
        dao.countByStatusSync(session.uid, "CONFLICT")
    }.getOrDefault(0)
  }

  private fun failShutdown(
    session: ActiveUserSession,
    pendingLocalCount: Int,
    message: String,
    onStateChanged: (SignOutUiPrompt) -> Unit
  ): SignOutStepOutcome.Failed {
    val prompt = SignOutUiPrompt(
      uid = session.uid,
      uidHash = session.uidHash,
      state = SignOutFlowState.SIGNOUT_FAILED,
      pendingLocalSyncCount = pendingLocalCount,
      errorMessage = message
    )
    onStateChanged(prompt)
    return SignOutStepOutcome.Failed(prompt)
  }
}
