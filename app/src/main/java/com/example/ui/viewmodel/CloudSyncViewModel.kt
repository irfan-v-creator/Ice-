package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.remote.FirestoreErrorHandler
import com.example.data.sync.CloudSyncPausedException
import com.example.data.sync.CloudSyncRepository
import com.example.data.sync.CloudSyncSafetyGate
import com.example.data.sync.CloudSyncSafetyPolicy
import com.example.data.sync.SyncBlockReason
import com.example.data.sync.SyncSummary
import com.example.data.sync.SyncWorkspaceMetaEntity
import com.example.domain.engine.ConflictCandidate
import com.example.domain.engine.ConflictResolutionStrategy
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CloudSyncUiState(
  val uid: String = "",
  val userEmail: String = "",
  val userDisplayName: String = "",
  val isSyncing: Boolean = false,
  val isOfflineQueueReady: Boolean = true,
  val isCloudSyncPaused: Boolean = CloudSyncSafetyGate.isPhase1SyncPaused,
  val reconciliationRequired: Boolean = false,
  val pauseBannerMessage: String? = if (CloudSyncSafetyGate.isPhase1SyncPaused) {
    SyncBlockReason.PHASE1_PAUSED.userMessage
  } else null,
  val syncedCount: Int = 0,
  val pendingCount: Int = 0,
  val conflictCount: Int = 0,
  val failedCount: Int = 0,
  val lastSyncedAtEpochMs: Long? = null,
  val lastSummaryMessage: String? = null,
  val errorMessage: String? = null,
  val conflicts: List<ConflictCandidate> = emptyList(),
  val remoteLastSyncEpochMs: Long? = null
)

class CloudSyncViewModel(
  private val uid: String,
  private val userEmail: String,
  private val userDisplayName: String,
  private val syncRepository: CloudSyncRepository,
  private val syncGate: CloudSyncSafetyPolicy = CloudSyncSafetyGate,
  private val isReconciliationRequired: () -> Boolean = { false }
) : ViewModel() {

  private val _busyAndStatus = MutableStateFlow(
    Triple<Boolean, String?, String?>(false, null, null)
  )

  private val remoteWorkspaceStream: Flow<Long?> =
    syncRepository.observeRemoteWorkspaceVersion(uid)
      .catch { error ->
        val firestoreEx = FirestoreErrorHandler.unwrapFirestoreException(error)
        if (firestoreEx != null) {
          FirestoreErrorHandler.handleFirestoreError(
            firestoreEx,
            com.example.data.remote.OperationType.GET,
            "users/$uid"
          )
          _busyAndStatus.value = Triple(
            _busyAndStatus.value.first,
            _busyAndStatus.value.second,
            FirestoreErrorHandler.userFriendlyMessage(firestoreEx)
          )
          emit(null)
        } else if (error is CancellationException) {
          throw error
        } else {
          emit(null)
        }
      }

  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<CloudSyncUiState> = combine(
    syncRepository.observeWorkspaceMeta(uid),
    syncRepository.observeConflicts(uid),
    remoteWorkspaceStream,
    _busyAndStatus
  ) { meta: SyncWorkspaceMetaEntity?, conflicts, remoteSyncMs, busyTuple ->
    val (isBusy, summaryMsg, uiErr) = busyTuple
    val reconcile = isReconciliationRequired()
    val paused = syncGate.isPhase1SyncPaused || reconcile
    val pauseBanner = when {
      reconcile -> SyncBlockReason.RECOVERY_REQUIRED.userMessage
      syncGate.isPhase1SyncPaused -> SyncBlockReason.PHASE1_PAUSED.userMessage
      else -> null
    }
    CloudSyncUiState(
      uid = uid,
      userEmail = userEmail,
      userDisplayName = userDisplayName,
      isSyncing = isBusy,
      isCloudSyncPaused = paused,
      reconciliationRequired = reconcile,
      pauseBannerMessage = pauseBanner,
      syncedCount = meta?.syncedCount ?: 0,
      pendingCount = meta?.pendingCount ?: 0,
      conflictCount = conflicts.size,
      failedCount = meta?.failedCount ?: 0,
      lastSyncedAtEpochMs = meta?.lastSuccessfulSyncAtMs,
      lastSummaryMessage = summaryMsg,
      errorMessage = uiErr ?: meta?.lastErrorMessage,
      conflicts = conflicts,
      remoteLastSyncEpochMs = remoteSyncMs
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000L),
    initialValue = CloudSyncUiState(
      uid = uid,
      userEmail = userEmail,
      userDisplayName = userDisplayName,
      isCloudSyncPaused = syncGate.isPhase1SyncPaused || isReconciliationRequired(),
      reconciliationRequired = isReconciliationRequired(),
      pauseBannerMessage = when {
        isReconciliationRequired() -> SyncBlockReason.RECOVERY_REQUIRED.userMessage
        syncGate.isPhase1SyncPaused -> SyncBlockReason.PHASE1_PAUSED.userMessage
        else -> null
      }
    )
  )

  init {
    refreshLocalQueueSummaryIfSignedIn()
  }

  /**
   * Phase 1 Option 1: App launch refreshes ONLY the local pending sync queue summary
   * in Room without invoking Firestore or auto-starting cloud sync.
   */
  fun refreshLocalQueueSummaryIfSignedIn() {
    if (FirebaseAuth.getInstance().currentUser?.uid != uid) return
    viewModelScope.launch {
      runCatching { syncRepository.refreshLocalPendingQueueSummary() }
    }
  }

  fun syncNow(forceRetryFailed: Boolean = true) {
    if (_busyAndStatus.value.first) return
    viewModelScope.launch {
      _busyAndStatus.value = Triple(true, null, null)
      val result: Result<SyncSummary> = syncRepository.syncNow(forceRetryFailed = forceRetryFailed)
      result.fold(
        onSuccess = { summary ->
          val msg = "Cloud sync complete: ${summary.uploadedCount} uploaded, ${summary.pulledCount} pulled" +
            (if (summary.conflictCount > 0) ", ${summary.conflictCount} conflicts need review" else "") +
            (if (summary.skippedRetryBackoffCount > 0) " (${summary.skippedRetryBackoffCount} queued for backoff)" else "")
          _busyAndStatus.value = Triple(false, msg, null)
        },
        onFailure = { error ->
          val msg = if (error is CloudSyncPausedException) {
            error.message ?: SyncBlockReason.PHASE1_PAUSED.userMessage
          } else {
            FirestoreErrorHandler.userFriendlyMessage(error)
          }
          _busyAndStatus.value = Triple(false, null, msg)
        }
      )
    }
  }

  fun resolveConflict(
    candidate: ConflictCandidate,
    strategy: ConflictResolutionStrategy
  ) {
    if (_busyAndStatus.value.first) return
    viewModelScope.launch {
      _busyAndStatus.value = Triple(true, null, null)
      val result = syncRepository.resolveConflict(candidate, strategy)
      result.fold(
        onSuccess = {
          _busyAndStatus.value = Triple(false, "Conflict resolved for ${candidate.displayLabel}.", null)
        },
        onFailure = { error ->
          val msg = if (error is CloudSyncPausedException) {
            error.message ?: SyncBlockReason.PHASE1_PAUSED.userMessage
          } else {
            FirestoreErrorHandler.userFriendlyMessage(error)
          }
          _busyAndStatus.value = Triple(false, null, msg)
        }
      )
    }
  }

  fun clearMessages() {
    _busyAndStatus.value = Triple(_busyAndStatus.value.first, null, null)
  }

  suspend fun cleanupOnSignOut(): Result<Unit> {
    return syncRepository.detachAllListenersStrict()
  }

  class Factory(
    private val uid: String,
    private val userEmail: String,
    private val userDisplayName: String,
    private val syncRepository: CloudSyncRepository,
    private val syncGate: CloudSyncSafetyPolicy = CloudSyncSafetyGate,
    private val isReconciliationRequired: () -> Boolean = { false }
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return CloudSyncViewModel(
        uid = uid,
        userEmail = userEmail,
        userDisplayName = userDisplayName,
        syncRepository = syncRepository,
        syncGate = syncGate,
        isReconciliationRequired = isReconciliationRequired
      ) as T
    }
  }
}
