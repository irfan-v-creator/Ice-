package com.example.session

import android.content.Context
import com.example.data.local.DatabaseIntegrityManager
import com.example.data.sync.CloudSyncSafetyGate
import com.example.data.sync.CloudSyncSafetyPolicy
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SessionState {
  data object SignedOut : SessionState
  data class RecoveryRequired(
    val marker: SignOutRecoveryMarker,
    val errorMessage: String
  ) : SessionState
  data class Initializing(val uid: String, val email: String) : SessionState
  data class LegacyMigrationPrompt(
    val user: AuthUserDescriptor,
    val assessment: LegacyMigrationAssessment,
    val isMigrating: Boolean = false,
    val errorMessage: String? = null
  ) : SessionState
  data class Active(val session: ActiveUserSession) : SessionState
  data class SigningOut(
    val session: ActiveUserSession?,
    val prompt: SignOutUiPrompt
  ) : SessionState
  data class Error(
    val uid: String?,
    val message: String
  ) : SessionState
}

/**
 * Single Source of Truth for authenticated user sessions across the entire Ice Tracker app.
 *
 * Guarantees:
 * - No business database is opened without an authenticated UID.
 * - Interrupted sign-out recovery executes BEFORE opening any database or Cloud Sync.
 * - Account switching tears down the outgoing UID's ViewModels, jobs, listeners, and Room DBs
 *   under a single Mutex before opening the incoming UID's isolated DBs.
 * - Concurrent auth events / session initialization calls are serialized cleanly.
 */
class SessionScopeManager(
  context: Context,
  private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
  private val markerStore: SignOutMarkerStore = SignOutMarkerStore(context),
  private val authGateway: AuthSessionGateway = DefaultAuthSessionGateway(context, auth),
  private val firestoreGateway: FirestoreLifecycleGateway = DefaultFirestoreLifecycleGateway(context),
  private val databaseGateway: DatabaseLifecycleGateway = DefaultDatabaseLifecycleGateway(context),
  private val syncSafetyPolicy: CloudSyncSafetyPolicy = CloudSyncSafetyGate,
  private val legacyMigrationManager: LegacyDatabaseMigrationManager = LegacyDatabaseMigrationManager(context, markerStore),
  drainTimeoutMs: Long = 5_000L,
  firestoreAckTimeoutMs: Long = 4_000L
) {
  private val appContext = context.applicationContext
  private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val sessionMutex = Mutex()
  private val tokenGenerator = AtomicLong(0L)

  private val signOutCoordinator = SignOutCoordinator(
    markerStore = markerStore,
    authGateway = authGateway,
    firestoreGateway = firestoreGateway,
    databaseGateway = databaseGateway,
    drainTimeoutMs = drainTimeoutMs,
    firestoreAckTimeoutMs = firestoreAckTimeoutMs
  )

  private val _sessionState = MutableStateFlow<SessionState>(SessionState.SignedOut)
  val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

  @Volatile
  private var activeSession: ActiveUserSession? = null

  @Volatile
  private var pendingSignOutSession: ActiveUserSession? = null

  @Volatile
  private var observationStarted = false

  @Volatile
  private var signOutInProgress = false

  fun currentActiveSessionOrNull(): ActiveUserSession? = activeSession

  fun startObservingAuth() {
    if (observationStarted) return
    synchronized(this) {
      if (observationStarted) return
      observationStarted = true
    }
    managerScope.launch {
      // Always run startup crash-recovery check BEFORE processing auth users / opening DBs
      val recoveryDone = runRecoveryInternal()
      if (!recoveryDone) {
        return@launch
      }
      authGateway.authStateFlow()
        .distinctUntilChanged { old, new -> old?.uid == new?.uid }
        .collect { user ->
          handleAuthUserChanged(user)
        }
    }
  }

  suspend fun retryStartupRecovery() {
    val ok = runRecoveryInternal()
    if (ok) {
      handleAuthUserChanged(authGateway.currentUser)
    }
  }

  private suspend fun runRecoveryInternal(): Boolean = sessionMutex.withLock {
    val pendingMarker = markerStore.readPendingMarker() ?: return@withLock true
    val res = signOutCoordinator.runStartupRecoveryIfNeeded()
    if (res.isFailure) {
      _sessionState.value = SessionState.RecoveryRequired(
        marker = pendingMarker,
        errorMessage = res.exceptionOrNull()?.message
          ?: "Pending sign-out recovery failed. Retry before accessing account data."
      )
      return@withLock false
    }
    true
  }

  suspend fun handleAuthUserChanged(user: AuthUserDescriptor?) {
    if (signOutInProgress) {
      // Explicit sign-out state machine drives transition to SignedOut after safe teardown
      return
    }
    sessionMutex.withLock {
      if (signOutInProgress) return@withLock

      // Block all DB opens if crash recovery marker is still unresolved
      val unresolvedMarker = markerStore.readPendingMarker()
      if (unresolvedMarker != null) {
        val recoveryRes = signOutCoordinator.runStartupRecoveryIfNeeded()
        if (recoveryRes.isFailure) {
          _sessionState.value = SessionState.RecoveryRequired(
            marker = unresolvedMarker,
            errorMessage = recoveryRes.exceptionOrNull()?.message
              ?: "Recovery cleanup must complete before opening account workspace."
          )
          return@withLock
        }
      }

      if (user == null || user.uid.isBlank()) {
        tearDownActiveSessionLocked()
        _sessionState.value = SessionState.SignedOut
        return@withLock
      }

      val current = activeSession
      if (current != null && current.uid == user.uid) {
        return@withLock
      }

      // Account switch or cold login: cleanly tear down outgoing UID session first!
      if (current != null) {
        val tornDown = tearDownActiveSessionLocked()
        if (!tornDown) {
          return@withLock
        }
      }

      initializeSessionForUserLocked(user, bypassMigrationPrompt = false)
    }
  }

  private suspend fun initializeSessionForUserLocked(
    user: AuthUserDescriptor,
    bypassMigrationPrompt: Boolean
  ) {
    _sessionState.value = SessionState.Initializing(uid = user.uid, email = user.email)
    try {
      if (!bypassMigrationPrompt) {
        val assessment = legacyMigrationManager.assessForUser(user.uid)
        when (assessment) {
          is LegacyMigrationAssessment.RequiresOwnerConfirmation,
          is LegacyMigrationAssessment.OwnershipMismatchBlocked -> {
            _sessionState.value = SessionState.LegacyMigrationPrompt(
              user = user,
              assessment = assessment
            )
            return
          }
          LegacyMigrationAssessment.NoLegacyData,
          LegacyMigrationAssessment.AlreadyMigratedToCurrentUser,
          LegacyMigrationAssessment.ClaimedByAnotherAccount -> Unit
        }
      }

      val session = withContext(Dispatchers.IO) {
        val uidHash = DatabaseIntegrityManager.uidToSafeHash(user.uid)
        val businessDb = databaseGateway.openBusinessDb(user.uid)
        val syncDb = databaseGateway.openSyncDb(user.uid)
        val reconcileRequired = markerStore.isReconciliationRequired(uidHash)
        ActiveUserSession(
          uid = user.uid,
          uidHash = uidHash,
          userEmail = user.email,
          userDisplayName = user.displayName,
          sessionToken = tokenGenerator.incrementAndGet(),
          database = businessDb,
          syncDatabase = syncDb,
          mutationGate = SessionMutationGate(),
          appContext = appContext,
          auth = auth,
          syncSafetyPolicy = syncSafetyPolicy,
          reconciliationRequired = reconcileRequired
        )
      }

      activeSession = session
      _sessionState.value = SessionState.Active(session)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      activeSession = null
      _sessionState.value = SessionState.Error(
        uid = user.uid,
        message = "Could not open isolated workspace: ${e.message ?: "Unknown error"}"
      )
    }
  }

  suspend fun exportRecoverableLegacyBackup(): Result<String> =
    legacyMigrationManager.exportRecoverableLegacyBackupJson()

  suspend fun confirmOwnerLegacyMigration(user: AuthUserDescriptor): LegacyMigrationExecutionResult =
    sessionMutex.withLock {
      val currentPrompt = _sessionState.value as? SessionState.LegacyMigrationPrompt
      if (currentPrompt != null) {
        _sessionState.value = currentPrompt.copy(isMigrating = true, errorMessage = null)
      }
      val result = legacyMigrationManager.migrateLegacyToUser(user.uid)
      if (!result.success) {
        if (currentPrompt != null) {
          _sessionState.value = currentPrompt.copy(
            isMigrating = false,
            errorMessage = result.errorMessage ?: "Legacy migration failed."
          )
        }
        return@withLock result
      }
      initializeSessionForUserLocked(user, bypassMigrationPrompt = true)
      result
    }

  suspend fun startFreshWithoutLegacyMigration(user: AuthUserDescriptor) = sessionMutex.withLock {
    legacyMigrationManager.deferLegacyMigrationForUser(user.uid)
    initializeSessionForUserLocked(user, bypassMigrationPrompt = true)
  }

  suspend fun signOutFromBlockedPrompt() = sessionMutex.withLock {
    authGateway.clearCredentialState()
    authGateway.signOutFirebase()
    activeSession = null
    _sessionState.value = SessionState.SignedOut
  }

  suspend fun requestSignOut(): SignOutStepOutcome = sessionMutex.withLock {
    val resumingNormal = pendingSignOutSession
    if (activeSession == null && resumingNormal != null) {
      if (markerStore.readPendingMarker() != null) {
        return@withLock executeDiscardForSessionLocked(resumingNormal)
      }
      signOutInProgress = true
      val outcome = signOutCoordinator.performSafeNormalShutdown(
        session = resumingNormal,
        pendingLocalCount = 0,
        onStateChanged = { prompt ->
          _sessionState.value = SessionState.SigningOut(
            session = if (prompt.state == SignOutFlowState.SIGNOUT_COMPLETED) null else activeSession,
            prompt = prompt
          )
        },
        onUnmountActiveSession = {
          activeSession = null
        }
      )
      handleOutcomeLocked(resumingNormal, outcome)
      return@withLock outcome
    }

    val session = activeSession ?: run {
      if (markerStore.readPendingMarker() != null) {
        val recoveryRes = signOutCoordinator.runStartupRecoveryIfNeeded()
        if (recoveryRes.isFailure) {
          val marker = markerStore.readPendingMarker()
          if (marker != null) {
            _sessionState.value = SessionState.RecoveryRequired(
              marker = marker,
              errorMessage = recoveryRes.exceptionOrNull()?.message ?: "Recovery cleanup failed."
            )
            return@withLock SignOutStepOutcome.Failed(
              SignOutUiPrompt(
                uid = "",
                uidHash = marker.uidHash,
                state = SignOutFlowState.SIGNOUT_FAILED,
                errorMessage = recoveryRes.exceptionOrNull()?.message
              )
            )
          }
        }
      }
      authGateway.clearCredentialState()
      authGateway.signOutFirebase()
      pendingSignOutSession = null
      _sessionState.value = SessionState.SignedOut
      return@withLock SignOutStepOutcome.Completed
    }
    pendingSignOutSession = session
    signOutInProgress = true
    try {
      val outcome = signOutCoordinator.beginNormalSignOut(
        session = session,
        onStateChanged = { prompt ->
          _sessionState.value = SessionState.SigningOut(
            session = if (prompt.state == SignOutFlowState.SIGNOUT_COMPLETED) null else activeSession,
            prompt = prompt
          )
        },
        onUnmountActiveSession = {
          activeSession = null
        }
      )
      handleOutcomeLocked(session, outcome)
      outcome
    } finally {
      if (_sessionState.value !is SessionState.SigningOut) {
        signOutInProgress = false
      }
    }
  }

  suspend fun retryWaitForPendingWrites(): SignOutStepOutcome = requestSignOut()

  suspend fun cancelPendingSignOut() = sessionMutex.withLock {
    val signingOut = _sessionState.value as? SessionState.SigningOut ?: return@withLock
    val session = signingOut.session ?: activeSession ?: pendingSignOutSession
    if (session != null && session.database.isOpen && session.syncDatabase.isOpen && markerStore.readPendingMarker() == null) {
      signOutCoordinator.cancelPendingSignOut(session)
      pendingSignOutSession = null
      activeSession = session
      _sessionState.value = SessionState.Active(session)
    } else if (signingOut.prompt.state == SignOutFlowState.SIGNOUT_FAILED || markerStore.readPendingMarker() != null) {
      // DB already closed or discard cleanup already committed; user must retry shutdown or restart
      return@withLock
    } else {
      pendingSignOutSession = null
      _sessionState.value = SessionState.SignedOut
    }
    signOutInProgress = false
  }

  suspend fun confirmDiscardPendingWritesAndSignOut(): SignOutStepOutcome = sessionMutex.withLock {
    val signingOut = _sessionState.value as? SessionState.SigningOut
    val session = signingOut?.session ?: activeSession ?: pendingSignOutSession
    if (session == null) {
      if (markerStore.readPendingMarker() != null) {
        val recoveryRes = signOutCoordinator.runStartupRecoveryIfNeeded()
        if (recoveryRes.isFailure) {
          val prompt = SignOutUiPrompt(
            uid = "",
            uidHash = markerStore.readPendingMarker()?.uidHash.orEmpty(),
            state = SignOutFlowState.SIGNOUT_FAILED,
            errorMessage = recoveryRes.exceptionOrNull()?.message ?: "Recovery cleanup failed."
          )
          _sessionState.value = SessionState.SigningOut(null, prompt)
          return@withLock SignOutStepOutcome.Failed(prompt)
        }
      }
      _sessionState.value = SessionState.SignedOut
      signOutInProgress = false
      return@withLock SignOutStepOutcome.Completed
    }
    executeDiscardForSessionLocked(session)
  }

  private suspend fun executeDiscardForSessionLocked(session: ActiveUserSession): SignOutStepOutcome {
    pendingSignOutSession = session
    signOutInProgress = true
    val outcome = signOutCoordinator.executeExplicitDiscardAndSignOut(
      session = session,
      onStateChanged = { prompt ->
        _sessionState.value = SessionState.SigningOut(
          session = if (prompt.state == SignOutFlowState.SIGNOUT_COMPLETED) null else activeSession,
          prompt = prompt
        )
      },
      onUnmountActiveSession = {
        activeSession = null
      }
    )
    handleOutcomeLocked(session, outcome)
    return outcome
  }

  private suspend fun handleOutcomeLocked(session: ActiveUserSession, outcome: SignOutStepOutcome) {
    when (outcome) {
      SignOutStepOutcome.Completed -> {
        activeSession = null
        pendingSignOutSession = null
        signOutInProgress = false
        _sessionState.value = SessionState.SignedOut
      }
      is SignOutStepOutcome.AwaitingUserDecision -> {
        // Keep session mounted reference in SigningOut prompt until user chooses Wait/Cancel/Discard
        activeSession = session
        pendingSignOutSession = session
        signOutInProgress = true
        _sessionState.value = SessionState.SigningOut(session, outcome.prompt)
      }
      is SignOutStepOutcome.Failed -> {
        // Never hide cleanup failure or lose the retry handle after unmount
        pendingSignOutSession = session
        signOutInProgress = true
        _sessionState.value = SessionState.SigningOut(activeSession, outcome.prompt)
      }
    }
  }

  private suspend fun tearDownActiveSessionLocked(): Boolean = withContext(NonCancellable) {
    val outgoing = activeSession ?: return@withContext true
    val drainRes = outgoing.mutationGate.freezeAndDrain(
      isDbInTransaction = { outgoing.database.inTransaction() || outgoing.syncDatabase.inTransaction() },
      timeoutMs = 5_000L
    )
    if (drainRes is DrainResult.TimedOut) {
      _sessionState.value = SessionState.Error(
        uid = outgoing.uid,
        message = "Cannot switch account while active database transaction is running."
      )
      return@withContext false
    }
    activeSession = null
    outgoing.cancelJobsAndClearViewModels()
    val bizClose = databaseGateway.closeBusinessDb(outgoing.uid)
    val syncClose = databaseGateway.closeSyncDb(outgoing.uid)
    if (bizClose.isFailure || syncClose.isFailure) {
      _sessionState.value = SessionState.Error(
        uid = outgoing.uid,
        message = "Failed to safely close previous account database."
      )
      return@withContext false
    }
    true
  }

  companion object {
    @Volatile
    private var INSTANCE: SessionScopeManager? = null

    fun getInstance(context: Context): SessionScopeManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: SessionScopeManager(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
