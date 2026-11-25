package com.example.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

class MutationsFrozenException(
  message: String = "Session mutations are frozen while account sign-out or shutdown is in progress."
) : IllegalStateException(message)

sealed interface DrainResult {
  data object Drained : DrainResult
  data class TimedOut(val remainingActiveMutations: Int, val dbInTransaction: Boolean) : DrainResult
}

/**
 * Coordinates safe write gating per active user session.
 * Prevents new business mutations or local sync staging while sign-out/account-switch teardown runs,
 * and allows shutdown to wait until in-flight writes and SQLite transactions finish cleanly.
 */
class SessionMutationGate {
  private val frozen = AtomicBoolean(false)
  private val activeMutations = AtomicInteger(0)
  private val stateLock = Any()

  fun isFrozen(): Boolean = frozen.get()

  fun activeMutationCount(): Int = activeMutations.get()

  fun checkCanMutate() {
    if (frozen.get()) {
      throw MutationsFrozenException()
    }
  }

  /**
   * Acquires an active mutation lease if not frozen, executes [block], and guarantees lease release.
   */
  suspend fun <T> withGuardedMutation(block: suspend () -> T): T {
    synchronized(stateLock) {
      if (frozen.get()) {
        throw MutationsFrozenException()
      }
      activeMutations.incrementAndGet()
    }
    return try {
      block()
    } finally {
      activeMutations.decrementAndGet()
    }
  }

  /**
   * Freezes all new mutations immediately and waits up to [timeoutMs] for already-started
   * mutations and SQLite transactions to drain cleanly. Never closes databases mid-transaction.
   */
  suspend fun freezeAndDrain(
    isDbInTransaction: () -> Boolean,
    timeoutMs: Long = 5_000L,
    pollIntervalMs: Long = 20L
  ): DrainResult {
    synchronized(stateLock) {
      frozen.set(true)
    }
    val deadlineMs = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
    while (true) {
      val pendingOps = activeMutations.get()
      val txRunning = runCatching { isDbInTransaction() }.getOrDefault(true)
      if (pendingOps <= 0 && !txRunning) {
        return DrainResult.Drained
      }
      if (System.currentTimeMillis() >= deadlineMs) {
        return DrainResult.TimedOut(
          remainingActiveMutations = pendingOps.coerceAtLeast(0),
          dbInTransaction = txRunning
        )
      }
      delay(pollIntervalMs)
    }
  }

  /**
   * Restores mutation capability only when a shutdown/sign-out attempt is cancelled or aborted
   * BEFORE session databases or ViewModels have been torn down.
   */
  fun unfreezeIfSafe() {
    synchronized(stateLock) {
      frozen.set(false)
    }
  }
}

internal suspend inline fun <T> guardSessionMutation(
  gate: SessionMutationGate?,
  crossinline block: suspend () -> T
): T {
  return if (gate != null) {
    gate.withGuardedMutation { block() }
  } else {
    block()
  }
}
