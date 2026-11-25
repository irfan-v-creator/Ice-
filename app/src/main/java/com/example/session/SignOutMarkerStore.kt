package com.example.session

import android.content.Context
import android.content.SharedPreferences

enum class SignOutMarkerPhase {
  NONE,
  DISCARD_AND_SIGNOUT_PENDING,
  CLEANUP_REQUIRED
}

data class SignOutRecoveryMarker(
  val phase: SignOutMarkerPhase,
  val uidHash: String,
  val updatedAtMs: Long
)

/**
 * Durable synchronous storage for crash-safe sign-out recovery markers, per-UID reconciliation
 * flags, and legacy global DB migration ownership claims.
 *
 * Uses SharedPreferences.commit() synchronously on small control flags to satisfy crash durability
 * guarantees before destructive Firestore persistence cleanup or session transitions.
 */
class SignOutMarkerStore(
  private val prefs: SharedPreferences,
  private val commitOverride: ((SharedPreferences.Editor) -> Boolean)? = null
) {

  constructor(context: Context) : this(
    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  )

  private fun commit(block: SharedPreferences.Editor.() -> Unit): Boolean {
    val editor = prefs.edit()
    editor.block()
    return commitOverride?.invoke(editor) ?: editor.commit()
  }

  fun persistDiscardPendingSync(uidHash: String, nowMs: Long = System.currentTimeMillis()): Boolean {
    return commit {
      putString(KEY_SIGNOUT_MARKER_PHASE, SignOutMarkerPhase.DISCARD_AND_SIGNOUT_PENDING.name)
      putString(KEY_SIGNOUT_MARKER_UID_HASH, uidHash)
      putLong(KEY_SIGNOUT_MARKER_TS, nowMs)
    }
  }

  fun persistCleanupRequiredSync(uidHash: String, nowMs: Long = System.currentTimeMillis()): Boolean {
    return commit {
      putString(KEY_SIGNOUT_MARKER_PHASE, SignOutMarkerPhase.CLEANUP_REQUIRED.name)
      putString(KEY_SIGNOUT_MARKER_UID_HASH, uidHash)
      putLong(KEY_SIGNOUT_MARKER_TS, nowMs)
    }
  }

  fun clearSignOutMarkerSync(): Boolean {
    return commit {
      remove(KEY_SIGNOUT_MARKER_PHASE)
      remove(KEY_SIGNOUT_MARKER_UID_HASH)
      remove(KEY_SIGNOUT_MARKER_TS)
    }
  }

  fun readPendingMarker(): SignOutRecoveryMarker? {
    val rawPhase = prefs.getString(KEY_SIGNOUT_MARKER_PHASE, null) ?: return null
    val phase = runCatching { SignOutMarkerPhase.valueOf(rawPhase) }.getOrNull() ?: return null
    if (phase == SignOutMarkerPhase.NONE) return null
    val uidHash = prefs.getString(KEY_SIGNOUT_MARKER_UID_HASH, null).orEmpty()
    val ts = prefs.getLong(KEY_SIGNOUT_MARKER_TS, 0L)
    return SignOutRecoveryMarker(phase = phase, uidHash = uidHash, updatedAtMs = ts)
  }

  fun persistReconciliationRequiredSync(uidHash: String, required: Boolean): Boolean {
    return commit {
      putBoolean("$KEY_RECONCILE_PREFIX$uidHash", required)
    }
  }

  fun isReconciliationRequired(uidHash: String): Boolean {
    return prefs.getBoolean("$KEY_RECONCILE_PREFIX$uidHash", false)
  }

  fun getLegacyClaimedOwnerUidHash(): String? {
    return prefs.getString(KEY_LEGACY_CLAIMED_UID_HASH, null)?.takeIf { it.isNotBlank() }
  }

  fun getLegacyPreMigrationBackupPath(uidHash: String): String? {
    return prefs.getString("$KEY_LEGACY_BACKUP_PATH_PREFIX$uidHash", null)
  }

  fun persistLegacyClaimedOwnerSync(
    uidHash: String,
    backupFilePath: String?,
    claimedAtMs: Long = System.currentTimeMillis()
  ): Boolean {
    return commit {
      putString(KEY_LEGACY_CLAIMED_UID_HASH, uidHash)
      putLong(KEY_LEGACY_CLAIMED_AT_MS, claimedAtMs)
      if (!backupFilePath.isNullOrBlank()) {
        putString("$KEY_LEGACY_BACKUP_PATH_PREFIX$uidHash", backupFilePath)
      }
    }
  }

  fun isLegacyMigrationDeferredForUid(uidHash: String): Boolean {
    return prefs.getBoolean("$KEY_LEGACY_DEFERRED_PREFIX$uidHash", false)
  }

  fun setLegacyMigrationDeferredForUid(uidHash: String, deferred: Boolean): Boolean {
    return commit {
      putBoolean("$KEY_LEGACY_DEFERRED_PREFIX$uidHash", deferred)
    }
  }

  companion object {
    const val PREFS_NAME = "icetrack_session_safety_prefs"
    private const val KEY_SIGNOUT_MARKER_PHASE = "signout_marker_phase"
    private const val KEY_SIGNOUT_MARKER_UID_HASH = "signout_marker_uid_hash"
    private const val KEY_SIGNOUT_MARKER_TS = "signout_marker_ts"
    private const val KEY_RECONCILE_PREFIX = "reconciliation_required_"
    private const val KEY_LEGACY_CLAIMED_UID_HASH = "legacy_claimed_owner_uid_hash"
    private const val KEY_LEGACY_CLAIMED_AT_MS = "legacy_claimed_at_ms"
    private const val KEY_LEGACY_BACKUP_PATH_PREFIX = "legacy_backup_path_"
    private const val KEY_LEGACY_DEFERRED_PREFIX = "legacy_deferred_"
  }
}
