package com.example.data.sync

import com.example.session.SessionMutationGate

class CloudSyncPausedException(
  val reason: SyncBlockReason,
  message: String
) : IllegalStateException(message)

enum class SyncBlockReason(val userMessage: String) {
  PHASE1_PAUSED(
    "Cloud Sync is paused during Phase 1 Data Safety Mode. Local changes remain safely saved on this device."
  ),
  SESSION_MUTATIONS_FROZEN(
    "Cloud Sync blocked: account sign-out or session shutdown is in progress."
  ),
  RECOVERY_REQUIRED(
    "Cloud Sync blocked: interrupted sign-out cleanup or reconciliation check must finish first."
  ),
  UNAUTHENTICATED(
    "Cloud Sync blocked: Google Sign-In required."
  )
}

sealed interface SyncGateDecision {
  data object Allowed : SyncGateDecision
  data class Blocked(val reason: SyncBlockReason, val details: String) : SyncGateDecision
}

/**
 * Centralized Cloud Sync safety gate (Single Source of Truth).
 * Phase 1 owner authorization mandates Option 1: automatic, background, and manual Cloud Sync
 * are paused while Firebase Auth, local sync queue records, and remote data stay intact.
 */
interface CloudSyncSafetyPolicy {
  val isPhase1SyncPaused: Boolean

  fun checkSyncAllowed(
    uid: String?,
    mutationGate: SessionMutationGate? = null,
    recoveryPending: Boolean = false
  ): SyncGateDecision
}

object CloudSyncSafetyGate : CloudSyncSafetyPolicy {

  /**
   * Confirmed Owner Decision (Option 1 — Phase 1 Cloud Sync Pause).
   * Must NOT be enabled automatically without explicit owner authorization after Phase 1 verification.
   */
  const val OWNER_PHASE1_CLOUD_SYNC_PAUSED: Boolean = true

  @Volatile
  var phase1SyncPausedOverride: Boolean? = null

  override val isPhase1SyncPaused: Boolean
    get() = phase1SyncPausedOverride ?: OWNER_PHASE1_CLOUD_SYNC_PAUSED

  override fun checkSyncAllowed(
    uid: String?,
    mutationGate: SessionMutationGate?,
    recoveryPending: Boolean
  ): SyncGateDecision {
    if (uid.isNullOrBlank()) {
      return SyncGateDecision.Blocked(
        reason = SyncBlockReason.UNAUTHENTICATED,
        details = SyncBlockReason.UNAUTHENTICATED.userMessage
      )
    }
    if (recoveryPending) {
      return SyncGateDecision.Blocked(
        reason = SyncBlockReason.RECOVERY_REQUIRED,
        details = SyncBlockReason.RECOVERY_REQUIRED.userMessage
      )
    }
    if (mutationGate?.isFrozen() == true) {
      return SyncGateDecision.Blocked(
        reason = SyncBlockReason.SESSION_MUTATIONS_FROZEN,
        details = SyncBlockReason.SESSION_MUTATIONS_FROZEN.userMessage
      )
    }
    if (isPhase1SyncPaused) {
      return SyncGateDecision.Blocked(
        reason = SyncBlockReason.PHASE1_PAUSED,
        details = SyncBlockReason.PHASE1_PAUSED.userMessage
      )
    }
    return SyncGateDecision.Allowed
  }

  fun canAutoSync(
    uid: String?,
    mutationGate: SessionMutationGate? = null,
    recoveryPending: Boolean = false
  ): Boolean = checkSyncAllowed(uid, mutationGate, recoveryPending) is SyncGateDecision.Allowed

  fun canRunBackgroundWorker(
    uid: String?,
    recoveryPending: Boolean = false
  ): Boolean = checkSyncAllowed(uid, mutationGate = null, recoveryPending = recoveryPending) is SyncGateDecision.Allowed
}
