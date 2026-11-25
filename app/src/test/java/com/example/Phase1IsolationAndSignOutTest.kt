package com.example

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DatabaseIntegrityManager
import com.example.data.local.IceTrackDatabase
import com.example.data.local.entity.ProductEntity
import com.example.data.local.entity.ProductUnitEntity
import com.example.data.repository.CustomerRepository
import com.example.data.repository.ProductRepository
import com.example.data.repository.SettingsRepository
import com.example.data.sync.CloudSyncPausedException
import com.example.data.sync.CloudSyncRepository
import com.example.data.sync.CloudSyncSafetyGate
import com.example.data.sync.IceTrackSyncDatabase
import com.example.data.sync.SyncBlockReason
import com.example.data.sync.SyncRecordStateEntity
import com.example.domain.engine.ConflictCandidate
import com.example.domain.engine.ConflictResolutionStrategy
import com.example.domain.engine.SyncStatus
import com.example.domain.model.Customer
import com.example.domain.model.Product
import com.example.session.AuthSessionGateway
import com.example.session.AuthUserDescriptor
import com.example.session.DatabaseLifecycleGateway
import com.example.session.DefaultDatabaseLifecycleGateway
import com.example.session.FirestoreLifecycleGateway
import com.example.session.LegacyDatabaseMigrationManager
import com.example.session.LegacyMigrationAssessment
import com.example.session.LegacyOwnershipEvidence
import com.example.session.MutationsFrozenException
import com.example.session.SessionMutationGate
import com.example.session.SessionScopeManager
import com.example.session.SessionState
import com.example.session.SignOutCoordinator
import com.example.session.SignOutFlowState
import com.example.session.SignOutMarkerPhase
import com.example.session.SignOutMarkerStore
import com.example.session.SignOutStepOutcome
import com.example.ui.auth.GoogleSignInManager
import com.example.ui.viewmodel.CloudSyncViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1IsolationAndSignOutTest {

  private lateinit var context: Context
  private lateinit var auth: FirebaseAuth
  private val openedUids = mutableSetOf<String>()

  private val uidA = "user_alpha_uid_1001"
  private val uidB = "user_beta_uid_2002"
  private val userA = AuthUserDescriptor(uidA, "alpha@icetracker.test", "Alpha Owner")
  private val userB = AuthUserDescriptor(uidB, "beta@icetracker.test", "Beta Owner")

  private class FakeAuthSessionGateway(
    initialUser: AuthUserDescriptor? = null,
    private val clearCredentialResult: () -> Result<Unit> = { Result.success(Unit) },
    private val signOutResult: () -> Result<Unit> = { Result.success(Unit) }
  ) : AuthSessionGateway {
    val flow = MutableStateFlow(initialUser)
    var clearCredentialCalls = 0
    var signOutCalls = 0

    override val currentUser: AuthUserDescriptor?
      get() = flow.value

    override fun authStateFlow(): Flow<AuthUserDescriptor?> = flow

    override suspend fun clearCredentialState(): Result<Unit> {
      clearCredentialCalls++
      return clearCredentialResult()
    }

    override suspend fun signOutFirebase(): Result<Unit> {
      signOutCalls++
      val res = signOutResult()
      if (res.isSuccess) {
        flow.value = null
      }
      return res
    }
  }

  private class FakeFirestoreLifecycleGateway(
    var hasActiveInstance: Boolean = false,
    var waitResult: Result<Boolean> = Result.success(true),
    var clearResult: Result<Unit> = Result.success(Unit)
  ) : FirestoreLifecycleGateway {
    var waitCalls = 0
    var terminateAndClearCalls = 0

    override fun hasActiveInMemoryInstance(): Boolean = hasActiveInstance

    override suspend fun waitForPendingWrites(timeoutMs: Long): Result<Boolean> {
      waitCalls++
      return waitResult
    }

    override suspend fun terminateAndClearPersistence(): Result<Unit> {
      terminateAndClearCalls++
      val res = clearResult
      if (res.isSuccess) {
        hasActiveInstance = false
      }
      return res
    }
  }

  private class RecordingDatabaseGateway(
    private val context: Context,
    private val delegate: DatabaseLifecycleGateway = DefaultDatabaseLifecycleGateway(context)
  ) : DatabaseLifecycleGateway {
    val openBusinessCalls = mutableListOf<String>()
    val closeBusinessCalls = mutableListOf<String>()
    val closeSyncCalls = mutableListOf<String>()
    var forceCloseBusinessFailure: Boolean = false

    override fun openBusinessDb(uid: String): IceTrackDatabase {
      openBusinessCalls.add(uid)
      return delegate.openBusinessDb(uid)
    }

    override fun openSyncDb(uid: String): IceTrackSyncDatabase = delegate.openSyncDb(uid)

    override fun closeBusinessDb(uid: String): Result<Unit> {
      closeBusinessCalls.add(uid)
      if (forceCloseBusinessFailure) {
        return Result.failure(IllegalStateException("Simulated business DB close failure"))
      }
      return delegate.closeBusinessDb(uid)
    }

    override fun closeSyncDb(uid: String): Result<Unit> {
      closeSyncCalls.add(uid)
      return delegate.closeSyncDb(uid)
    }
  }

  class ProbeViewModel : ViewModel() {
    val cleared = AtomicBoolean(false)
    val runningJob = viewModelScope.launch {
      while (isActive) {
        delay(50L)
      }
    }

    override fun onCleared() {
      cleared.set(true)
      super.onCleared()
    }
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(
        context,
        FirebaseOptions.Builder()
          .setProjectId("icetrack-test-project")
          .setApplicationId("1:1234567890:android:abcdef123456")
          .setApiKey("AIzaSyTestDummyApiKeyNotUsedOnline12345")
          .build()
      )
    }
    auth = FirebaseAuth.getInstance()
    CloudSyncSafetyGate.phase1SyncPausedOverride = null
    cleanupTestArtifacts()
  }

  @After
  fun tearDown() {
    CloudSyncSafetyGate.phase1SyncPausedOverride = null
    cleanupTestArtifacts()
  }

  private fun cleanupTestArtifacts() {
    val allTestUids = openedUids + setOf(uidA, uidB, "uid_owner_legacy", "uid_intruder_legacy", "uid_sync_test")
    allTestUids.forEach { uid ->
      runCatching { IceTrackDatabase.closeForUid(uid) }
      runCatching { IceTrackSyncDatabase.closeForUid(uid) }
      deleteDbAndCompanions(DatabaseIntegrityManager.uidBusinessDbName(uid))
      deleteDbAndCompanions(DatabaseIntegrityManager.uidSyncDbName(uid))
    }
    openedUids.clear()
    deleteDbAndCompanions(IceTrackDatabase.LEGACY_GLOBAL_DB_NAME)
    deleteDbAndCompanions(IceTrackSyncDatabase.LEGACY_GLOBAL_SYNC_DB_NAME)
    context.getSharedPreferences(SignOutMarkerStore.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    File(context.filesDir, "legacy_backups").deleteRecursively()
  }

  private fun deleteDbAndCompanions(dbName: String) {
    val base = context.getDatabasePath(dbName)
    listOf(
      base,
      File("${base.absolutePath}-wal"),
      File("${base.absolutePath}-shm"),
      File("${base.absolutePath}-journal")
    ).forEach { if (it.exists()) it.delete() }
  }

  private fun createManager(
    authGateway: AuthSessionGateway,
    firestoreGateway: FirestoreLifecycleGateway = FakeFirestoreLifecycleGateway(),
    databaseGateway: DatabaseLifecycleGateway = DefaultDatabaseLifecycleGateway(context),
    markerStore: SignOutMarkerStore = SignOutMarkerStore(context),
    drainTimeoutMs: Long = 1_500L,
    firestoreAckTimeoutMs: Long = 500L
  ): SessionScopeManager {
    return SessionScopeManager(
      context = context,
      auth = auth,
      markerStore = markerStore,
      authGateway = authGateway,
      firestoreGateway = firestoreGateway,
      databaseGateway = databaseGateway,
      syncSafetyPolicy = CloudSyncSafetyGate,
      legacyMigrationManager = LegacyDatabaseMigrationManager(context, markerStore),
      drainTimeoutMs = drainTimeoutMs,
      firestoreAckTimeoutMs = firestoreAckTimeoutMs
    )
  }

  @Test
  fun unauthenticatedGlobalDbAccess_isBlockedAndNullUserOpensZeroDatabases() = runBlocking {
    var threwBiz = false
    try {
      IceTrackDatabase.Companion::class.java
        .getMethod("getInstance", Context::class.java)
        .invoke(IceTrackDatabase.Companion, context)
    } catch (e: java.lang.reflect.InvocationTargetException) {
      threwBiz = e.targetException is IllegalStateException
    }
    assertTrue("Global IceTrackDatabase.getInstance must throw IllegalStateException", threwBiz)

    var threwSync = false
    try {
      IceTrackSyncDatabase.Companion::class.java
        .getMethod("getInstance", Context::class.java)
        .invoke(IceTrackSyncDatabase.Companion, context)
    } catch (e: java.lang.reflect.InvocationTargetException) {
      threwSync = e.targetException is IllegalStateException
    }
    assertTrue("Global IceTrackSyncDatabase.getInstance must throw IllegalStateException", threwSync)

    val authGateway = FakeAuthSessionGateway(initialUser = null)
    val dbGateway = RecordingDatabaseGateway(context)
    val manager = createManager(authGateway = authGateway, databaseGateway = dbGateway)

    manager.handleAuthUserChanged(null)
    manager.handleAuthUserChanged(AuthUserDescriptor("   ", "", ""))

    assertEquals(SessionState.SignedOut, manager.sessionState.value)
    assertTrue("Signed-out user must open zero databases", dbGateway.openBusinessCalls.isEmpty())
    assertNull(manager.currentActiveSessionOrNull())
  }

  @Test
  fun perUidDatabaseIsolation_preventsCrossAccountLeakageAcrossAccountsAndSwitches() = runBlocking {
    openedUids.addAll(listOf(uidA, uidB))
    val authGateway = FakeAuthSessionGateway(initialUser = userA)
    val dbGateway = RecordingDatabaseGateway(context)
    val manager = createManager(authGateway = authGateway, databaseGateway = dbGateway)

    // 1. User A signs in and writes isolated data
    manager.handleAuthUserChanged(userA)
    val activeA = (manager.sessionState.value as SessionState.Active).session
    assertEquals(DatabaseIntegrityManager.uidToSafeHash(uidA), activeA.uidHash)

    val expectedBizFileA = context.getDatabasePath(DatabaseIntegrityManager.uidBusinessDbName(uidA))
    val expectedSyncFileA = context.getDatabasePath(DatabaseIntegrityManager.uidSyncDbName(uidA))
    assertTrue("User A business DB file must exist", expectedBizFileA.exists())
    assertTrue("User A sync DB file must exist", expectedSyncFileA.exists())

    activeA.productRepo.saveProductWithUnit(
      product = Product(
        name = "Alpha Special Cone",
        sku = "ALPHA-01",
        category = "Cones",
        defaultPurchasePricePaise = 1200L,
        defaultSellingPricePaise = 2000L,
        minStockLevelPieces = 10
      ),
      piecesPerBox = 12,
      boxesPerCarton = 2
    )
    activeA.customerRepo.saveCustomer(
      Customer(
        shopName = "Alpha Dairy Depot",
        ownerName = "Alpha Owner",
        phoneNumber = "+919000000001",
        address = "Kochi"
      )
    )
    activeA.settingsRepo.setSetting("business_name", "Alpha Ice Works")
    activeA.syncDatabase.syncStateDao().upsertRecord(
      SyncRecordStateEntity(
        ownerUid = uidA,
        entityType = "products",
        localKey = "1",
        localId = 1L,
        cloudDocId = "1",
        displayLabel = "Alpha Special Cone",
        syncStatus = SyncStatus.PENDING_UPLOAD.name,
        currentLocalHash = "hash_a1"
      )
    )

    // Register a ViewModel in User A's isolated ViewModelStore
    val vmA = ViewModelProvider(activeA.viewModelStoreOwner)[ProbeViewModel::class.java]
    assertFalse(vmA.cleared.get())

    // 2. Account switch A -> B
    authGateway.flow.value = userB
    manager.handleAuthUserChanged(userB)
    val activeB = (manager.sessionState.value as SessionState.Active).session

    assertNotEquals(activeA.sessionToken, activeB.sessionToken)
    assertNotEquals(activeA.uidHash, activeB.uidHash)
    assertTrue("User A DB must be closed on switch", dbGateway.closeBusinessCalls.contains(uidA))
    assertTrue("User A Sync DB must be closed on switch", dbGateway.closeSyncCalls.contains(uidA))
    assertTrue("User A ViewModel must be cleared on switch", vmA.cleared.get())
    assertTrue("User A sessionJob must be cancelled on switch", activeA.sessionJob.isCancelled)
    assertFalse("User A DB must no longer be open", activeA.database.isOpen)

    // 3. Verify User B sees ZERO data from User A
    assertEquals(0, activeB.database.productDao().getAllIncludingArchivedSync().size)
    assertEquals(0, activeB.database.customerDao().getAllIncludingArchivedSync().size)
    assertNull(activeB.database.appSettingDao().getSync("business_name"))
    assertEquals(0, activeB.syncDatabase.syncStateDao().getAllForUserSync(uidA).size)
    assertEquals(0, activeB.syncDatabase.syncStateDao().getAllForUserSync(uidB).size)

    // Write distinct data to User B and verify A retains its own when switching back
    activeB.productRepo.saveProductWithUnit(
      product = Product(
        name = "Beta Chocobar",
        sku = "BETA-01",
        category = "Bars",
        defaultPurchasePricePaise = 800L,
        defaultSellingPricePaise = 1500L,
        minStockLevelPieces = 20
      ),
      piecesPerBox = 24,
      boxesPerCarton = 2
    )

    authGateway.flow.value = userA
    manager.handleAuthUserChanged(userA)
    val reOpenedA = (manager.sessionState.value as SessionState.Active).session
    val productsA = reOpenedA.database.productDao().getAllIncludingArchivedSync()
    assertEquals(1, productsA.size)
    assertEquals("Alpha Special Cone", productsA.first().name)
    assertEquals("Alpha Ice Works", reOpenedA.database.appSettingDao().getSync("business_name")?.value)
  }

  @Test
  fun normalSignOut_preservesRoomAndSyncQueueAndBlocksActiveTransactionsSafely() = runBlocking {
    openedUids.add(uidA)
    val authGateway = FakeAuthSessionGateway(initialUser = userA)
    val firestoreGateway = FakeFirestoreLifecycleGateway(
      hasActiveInstance = true,
      waitResult = Result.success(true)
    )
    val manager = createManager(
      authGateway = authGateway,
      firestoreGateway = firestoreGateway,
      drainTimeoutMs = 200L
    )

    manager.handleAuthUserChanged(userA)
    val session = (manager.sessionState.value as SessionState.Active).session

    // Insert local business data + pending sync row
    session.productRepo.saveProductWithUnit(
      product = Product(
        name = "Mango Dolly",
        sku = "MD-01",
        category = "Sticks",
        defaultPurchasePricePaise = 500L,
        defaultSellingPricePaise = 1000L,
        minStockLevelPieces = 15
      ),
      piecesPerBox = 20,
      boxesPerCarton = 2
    )
    session.syncDatabase.syncStateDao().upsertRecord(
      SyncRecordStateEntity(
        ownerUid = uidA,
        entityType = "products",
        localKey = "1",
        localId = 1L,
        cloudDocId = "1",
        displayLabel = "Mango Dolly",
        syncStatus = SyncStatus.PENDING_UPLOAD.name,
        currentLocalHash = "hash_md"
      )
    )

    // Hold a guarded mutation longer than drainTimeoutMs -> sign-out MUST abort safely without losing data
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val longMutation = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
      session.mutationGate.withGuardedMutation {
        started.complete(Unit)
        release.await()
        "done"
      }
    }
    started.await()

    val blockedOutcome = manager.requestSignOut()
    assertTrue("Drain timeout must return Failed outcome", blockedOutcome is SignOutStepOutcome.Failed)
    assertTrue("DB must remain open when drain times out", session.database.isOpen)
    assertEquals(0, authGateway.signOutCalls)

    // Complete in-flight mutation, then verify normal sign-out succeeds and preserves data
    release.complete(Unit)
    assertEquals("done", longMutation.await())

    val normalOutcome = manager.requestSignOut()
    assertEquals(SignOutStepOutcome.Completed, normalOutcome)
    assertEquals(SessionState.SignedOut, manager.sessionState.value)
    assertEquals(1, firestoreGateway.waitCalls)
    assertEquals(0, firestoreGateway.terminateAndClearCalls)
    assertEquals(1, authGateway.clearCredentialCalls)
    assertEquals(1, authGateway.signOutCalls)

    // Sign back in as User A -> local business data & pending sync queue must still exist intact
    authGateway.flow.value = userA
    manager.handleAuthUserChanged(userA)
    val restored = (manager.sessionState.value as SessionState.Active).session
    assertEquals(1, restored.database.productDao().getAllIncludingArchivedSync().size)
    assertEquals(1, restored.syncDatabase.syncStateDao().countByStatusSync(uidA, SyncStatus.PENDING_UPLOAD.name))
  }

  @Test
  fun mutationGate_freezeBlocksNewMutationsAndWaitsForInFlightDrain() = runBlocking {
    openedUids.add(uidA)
    val authGateway = FakeAuthSessionGateway(initialUser = userA)
    val manager = createManager(authGateway = authGateway, drainTimeoutMs = 2_000L)
    manager.handleAuthUserChanged(userA)
    val session = (manager.sessionState.value as SessionState.Active).session

    val started = CompletableDeferred<Unit>()
    val allowFinish = CompletableDeferred<Unit>()

    // Start an active Room transaction via guarded mutation
    val inFlight = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
      session.productRepo.saveProductWithUnit(
        product = Product(
          name = "Drain Wait Product",
          sku = "DWP-01",
          category = "Tubs",
          defaultPurchasePricePaise = 4000L,
          defaultSellingPricePaise = 6500L,
          minStockLevelPieces = 5
        ),
        piecesPerBox = 1,
        boxesPerCarton = 1
      )
    }
    inFlight.await()

    // Now test freezeAndDrain while guarded lease is open, releasing mid-wait
    val leaseStarted = CompletableDeferred<Unit>()
    val leaseJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
      session.mutationGate.withGuardedMutation {
        leaseStarted.complete(Unit)
        delay(120L)
      }
    }
    leaseStarted.await()

    val signOutAsync = async { manager.requestSignOut() }
    delay(30L)
    assertTrue("Gate must immediately freeze during sign-out pre-gate", session.mutationGate.isFrozen())

    var newMutationRejected = false
    try {
      session.customerRepo.saveCustomer(
        Customer(
          shopName = "Rejected During SignOut",
          ownerName = "Test",
          phoneNumber = "+919111111111",
          address = "Test"
        )
      )
    } catch (_: MutationsFrozenException) {
      newMutationRejected = true
    }
    assertTrue("New mutations must fail with MutationsFrozenException once frozen", newMutationRejected)

    leaseJob.await()
    val signOutOutcome = signOutAsync.await()
    assertEquals(SignOutStepOutcome.Completed, signOutOutcome)
    assertFalse(session.database.isOpen)
  }

  @Test
  fun explicitDiscardSignOut_blocksOfflineWaitOffersChoicePersistsCommitAndAbortsOnCommitOrCloseFailure() = runBlocking {
    openedUids.add(uidA)
    val markerStore = SignOutMarkerStore(context)
    val authGateway = FakeAuthSessionGateway(initialUser = userA)
    val firestoreGateway = FakeFirestoreLifecycleGateway(
      hasActiveInstance = true,
      waitResult = Result.success(false) // simulate offline / unacknowledged writes
    )
    val dbGateway = RecordingDatabaseGateway(context)
    val manager = createManager(
      authGateway = authGateway,
      firestoreGateway = firestoreGateway,
      databaseGateway = dbGateway,
      markerStore = markerStore
    )

    manager.handleAuthUserChanged(userA)
    val session = (manager.sessionState.value as SessionState.Active).session
    session.productRepo.saveProductWithUnit(
      product = Product(
        name = "Offline Item",
        sku = "OFF-01",
        category = "Cones",
        defaultPurchasePricePaise = 1000L,
        defaultSellingPricePaise = 1500L,
        minStockLevelPieces = 4
      ),
      piecesPerBox = 6,
      boxesPerCarton = 2
    )
    session.syncDatabase.syncStateDao().upsertRecord(
      SyncRecordStateEntity(
        ownerUid = uidA,
        entityType = "products",
        localKey = "1",
        localId = 1L,
        cloudDocId = "1",
        displayLabel = "Offline Item",
        syncStatus = SyncStatus.PENDING_UPLOAD.name,
        currentLocalHash = "hash_off"
      )
    )

    // Attempt normal sign-out -> must pause at PENDING_WRITES_BLOCKED and never auto-discard!
    val decision = manager.requestSignOut()
    assertTrue(decision is SignOutStepOutcome.AwaitingUserDecision)
    val signingOutState = manager.sessionState.value as SessionState.SigningOut
    assertEquals(SignOutFlowState.PENDING_WRITES_BLOCKED, signingOutState.prompt.state)
    assertEquals(0, firestoreGateway.terminateAndClearCalls)
    assertEquals(0, authGateway.signOutCalls)

    // Test Cancel restores active session and unfreezes gate
    manager.cancelPendingSignOut()
    assertTrue(manager.sessionState.value is SessionState.Active)
    assertFalse(session.mutationGate.isFrozen())

    // Trigger blocked state again, then test commit() failure aborts discard BEFORE closing DB or clearing Firestore
    manager.requestSignOut()
    val failingCommitStore = SignOutMarkerStore(
      prefs = context.getSharedPreferences(SignOutMarkerStore.PREFS_NAME, Context.MODE_PRIVATE),
      commitOverride = { false }
    )
    val failingCoordinator = SignOutCoordinator(
      markerStore = failingCommitStore,
      authGateway = authGateway,
      firestoreGateway = firestoreGateway,
      databaseGateway = dbGateway
    )
    val abortOutcome = failingCoordinator.executeExplicitDiscardAndSignOut(
      session = session,
      onStateChanged = {},
      onUnmountActiveSession = { error("Should never unmount when commit() fails!") }
    )
    assertTrue("Failed commit() must abort discard sign-out", abortOutcome is SignOutStepOutcome.Failed)
    assertTrue("DB must remain open after commit() failure", session.database.isOpen)
    assertEquals(0, firestoreGateway.terminateAndClearCalls)

    // Now test failing DB close aborts BEFORE Firestore clearPersistence and preserves marker for recovery
    dbGateway.forceCloseBusinessFailure = true
    val dbCloseFailOutcome = manager.confirmDiscardPendingWritesAndSignOut()
    assertTrue(dbCloseFailOutcome is SignOutStepOutcome.Failed)
    assertEquals(0, firestoreGateway.terminateAndClearCalls)
    assertEquals(SignOutMarkerPhase.DISCARD_AND_SIGNOUT_PENDING, markerStore.readPendingMarker()?.phase)

    // Allow DB close & execute full explicit discard -> succeeds, marks reconciliationRequired, keeps local Room & queue!
    dbGateway.forceCloseBusinessFailure = false
    val discardOutcome = manager.confirmDiscardPendingWritesAndSignOut()
    assertEquals(SignOutStepOutcome.Completed, discardOutcome)
    assertEquals(1, firestoreGateway.terminateAndClearCalls)
    assertEquals(1, authGateway.signOutCalls)
    assertNull("Marker must be cleared after full completion", markerStore.readPendingMarker())
    assertTrue(
      "reconciliationRequired must be true after discarding pending Firestore cache",
      markerStore.isReconciliationRequired(DatabaseIntegrityManager.uidToSafeHash(uidA))
    )

    // Verify local Room data and local sync queue are intact upon next login
    authGateway.flow.value = userA
    manager.handleAuthUserChanged(userA)
    val reopened = (manager.sessionState.value as SessionState.Active).session
    assertTrue(reopened.reconciliationRequired)
    assertEquals(1, reopened.database.productDao().getAllIncludingArchivedSync().size)
    assertEquals(1, reopened.syncDatabase.syncStateDao().countByStatusSync(uidA, SyncStatus.PENDING_UPLOAD.name))
  }

  @Test
  fun failedPreSignOutCleanup_googleSignInManagerDoesNotSignOut() = runBlocking {
    var signOutWasCalled = false
    val result = GoogleSignInManager.signOutSafely(
      context = context,
      onBeforeSignOutCleanup = { Result.failure(IllegalStateException("Listener detach failed")) },
      auth = auth
    )
    assertTrue("GoogleSignInManager.signOutSafely must propagate failure", result.isFailure)
    assertFalse(signOutWasCalled)
  }

  @Test
  fun startupCrashRecovery_clearsInterruptedDiscardMarkerBeforeOpeningDbAndBlocksOnFailure() = runBlocking {
    openedUids.add(uidA)
    val uidHashA = DatabaseIntegrityManager.uidToSafeHash(uidA)
    val markerStore = SignOutMarkerStore(context)
    assertTrue(markerStore.persistDiscardPendingSync(uidHashA))

    // 1. Failing Firestore clear during startup -> enters RecoveryRequired & opens ZERO databases!
    val authGateway = FakeAuthSessionGateway(initialUser = userA)
    val failingFsGateway = FakeFirestoreLifecycleGateway(
      clearResult = Result.failure(IllegalStateException("Firestore terminate failed"))
    )
    val dbGateway = RecordingDatabaseGateway(context)
    val managerWithFailure = createManager(
      authGateway = authGateway,
      firestoreGateway = failingFsGateway,
      databaseGateway = dbGateway,
      markerStore = markerStore
    )

    managerWithFailure.handleAuthUserChanged(userA)
    assertTrue(
      "Manager must block in RecoveryRequired when startup recovery fails",
      managerWithFailure.sessionState.value is SessionState.RecoveryRequired
    )
    assertTrue("No business DB may be opened before crash recovery succeeds", dbGateway.openBusinessCalls.isEmpty())

    // 2. Fix Firestore gateway and retry startup recovery -> finishes terminate/clear, sets reconciliationRequired, signs out
    failingFsGateway.clearResult = Result.success(Unit)
    managerWithFailure.retryStartupRecovery()

    assertEquals(2, failingFsGateway.terminateAndClearCalls)
    assertEquals(1, authGateway.clearCredentialCalls)
    assertEquals(1, authGateway.signOutCalls)
    assertNull(markerStore.readPendingMarker())
    assertTrue(markerStore.isReconciliationRequired(uidHashA))
    assertEquals(SessionState.SignedOut, managerWithFailure.sessionState.value)
  }

  @Test
  fun viewModelAndJobCancellation_clearsOnClearedAndCancelsCoroutines() = runBlocking {
    openedUids.add(uidA)
    val authGateway = FakeAuthSessionGateway(initialUser = userA)
    val manager = createManager(authGateway = authGateway)
    manager.handleAuthUserChanged(userA)
    val session = (manager.sessionState.value as SessionState.Active).session

    val vm = ViewModelProvider(session.viewModelStoreOwner)[ProbeViewModel::class.java]
    assertTrue(vm.viewModelScope.isActive)
    assertTrue(session.sessionJob.isActive)

    session.cancelJobsAndClearViewModels()

    assertTrue("ViewModel.onCleared must run on session disposal", vm.cleared.get())
    assertFalse("viewModelScope must be cancelled", vm.viewModelScope.isActive)
    assertTrue("sessionJob must be cancelled", session.sessionJob.isCancelled)
  }

  @Test
  fun concurrentAuthStateTransitions_serializeSafelyWithoutMixedSessions() = runBlocking {
    openedUids.addAll(listOf(uidA, uidB))
    val authGateway = FakeAuthSessionGateway(initialUser = null)
    val manager = createManager(authGateway = authGateway)

    // Fire rapid overlapping transitions: A, null, B, A
    val jobs = listOf(
      async(Dispatchers.Default) { manager.handleAuthUserChanged(userA) },
      async(Dispatchers.Default) { manager.handleAuthUserChanged(null) },
      async(Dispatchers.Default) { manager.handleAuthUserChanged(userB) },
      async(Dispatchers.Default) { manager.handleAuthUserChanged(userA) }
    )
    jobs.awaitAll()

    // Final deterministic call settles to userB
    manager.handleAuthUserChanged(userB)
    val finalState = manager.sessionState.value
    assertTrue("Final serialized state must be Active(userB)", finalState is SessionState.Active)
    assertEquals(uidB, (finalState as SessionState.Active).session.uid)
  }

  @Test
  fun cloudSyncPausePolicy_blocksAutoWorkerManualAndRealtimeListenerWithoutFirestoreInit() = runBlocking {
    assertTrue("Phase 1 owner pause constant must be enabled", CloudSyncSafetyGate.OWNER_PHASE1_CLOUD_SYNC_PAUSED)
    assertTrue(CloudSyncSafetyGate.isPhase1SyncPaused)
    assertFalse(CloudSyncSafetyGate.canAutoSync(uidA))
    assertFalse(CloudSyncSafetyGate.canRunBackgroundWorker(uidA))

    val firestoreProviderCalls = AtomicInteger(0)
    val localDb = IceTrackDatabase.openForUid(context, uidA)
    val syncDb = IceTrackSyncDatabase.openForUid(context, uidA)
    openedUids.add(uidA)

    val repo = CloudSyncRepository(
      context = context,
      localDb = localDb,
      syncDb = syncDb,
      auth = auth,
      syncGate = CloudSyncSafetyGate,
      firestoreProvider = {
        firestoreProviderCalls.incrementAndGet()
        error("Firestore must never be initialized while Phase 1 Cloud Sync is paused")
      }
    )

    // 1. Realtime listener emits null and never triggers firestoreProvider
    val emittedVersion = withTimeout(1_000L) {
      repo.observeRemoteWorkspaceVersion(uidA).first()
    }
    assertNull(emittedVersion)
    assertEquals(0, firestoreProviderCalls.get())

    val gateDecision = CloudSyncSafetyGate.checkSyncAllowed(uid = uidA)
    assertTrue(gateDecision is com.example.data.sync.SyncGateDecision.Blocked)
    assertEquals(
      SyncBlockReason.PHASE1_PAUSED,
      (gateDecision as com.example.data.sync.SyncGateDecision.Blocked).reason
    )

    // 2. Manual syncNow fails with CloudSyncPausedException before touching Firestore
    val syncResult = repo.syncNow(forceRetryFailed = true)
    assertTrue(syncResult.isFailure)
    val syncEx = syncResult.exceptionOrNull()
    assertTrue(syncEx is CloudSyncPausedException)
    assertEquals(0, firestoreProviderCalls.get())

    // 3. Conflict resolution also blocked while Phase 1 Cloud Sync is paused
    val resolveRes = repo.resolveConflict(
      candidate = ConflictCandidate(
        entityType = "products",
        localId = 1L,
        cloudDocId = "1",
        displayLabel = "Test",
        localContentHash = "a",
        cloudContentHash = "b",
        lastSyncedHash = "c",
        localUpdatedAtMs = 1L,
        cloudUpdatedAtMs = 2L,
        localRevision = 1,
        cloudRevision = 2,
        cloudPayloadJson = "{}"
      ),
      strategy = ConflictResolutionStrategy.KEEP_LOCAL_OVERWRITE_CLOUD
    )
    assertTrue(resolveRes.isFailure)
    assertTrue(resolveRes.exceptionOrNull() is CloudSyncPausedException)
    assertEquals(0, firestoreProviderCalls.get())

    // 4. CloudSyncViewModel init exposes paused state and does NOT run Firestore sync
    val vm = CloudSyncViewModel(
      uid = uidA,
      userEmail = userA.email,
      userDisplayName = userA.displayName,
      syncRepository = repo,
      syncGate = CloudSyncSafetyGate
    )
    val ui = vm.uiState.value
    assertTrue(ui.isCloudSyncPaused)
    assertFalse(ui.isSyncing)
    assertNotNull(ui.pauseBannerMessage)
    assertEquals(0, firestoreProviderCalls.get())
  }

  @Test
  fun legacyDatabaseMigration_protectsOwnershipPreventsAutoAssignCreatesBackupAndBlocksDuplicates() = runBlocking {
    val ownerUid = "uid_owner_legacy"
    val intruderUid = "uid_intruder_legacy"
    openedUids.addAll(listOf(ownerUid, intruderUid))
    val markerStore = SignOutMarkerStore(context)
    val migrationManager = LegacyDatabaseMigrationManager(context, markerStore)

    // Populate legacy global DB ("icetrack_database.db") directly
    val legacyDb = IceTrackDatabase.openMigratedLegacyDatabase(context)
    val legacyProductRepo = ProductRepository(legacyDb)
    val prodId = legacyProductRepo.saveProductWithUnit(
      product = Product(
        name = "Legacy Vanilla Tub",
        sku = "LEG-VAN-01",
        category = "Tubs",
        defaultPurchasePricePaise = 10000L,
        defaultSellingPricePaise = 15000L,
        minStockLevelPieces = 4
      ),
      piecesPerBox = 4,
      boxesPerCarton = 2
    )
    SettingsRepository(legacyDb).setSetting("business_name", "Legacy Creamery")
    DatabaseIntegrityManager.closeSafely(legacyDb)

    // Case 1: Unassigned offline legacy DB -> NEVER auto-assigned; prompts RequiresOwnerConfirmation
    val unassignedAssessment = migrationManager.assessForUser(ownerUid)
    assertTrue(unassignedAssessment is LegacyMigrationAssessment.RequiresOwnerConfirmation)
    assertEquals(
      LegacyOwnershipEvidence.UNASSIGNED_OFFLINE_LEGACY,
      (unassignedAssessment as LegacyMigrationAssessment.RequiresOwnerConfirmation).evidence
    )
    assertEquals(1, unassignedAssessment.summary.productsCount)

    // Also test legacy sync DB ownership check blocking wrong account!
    val legacySyncDb = IceTrackSyncDatabase.openLegacySyncDatabase(context)
    legacySyncDb.syncStateDao().upsertRecord(
      SyncRecordStateEntity(
        ownerUid = ownerUid,
        entityType = "products",
        localKey = prodId.toString(),
        localId = prodId,
        cloudDocId = prodId.toString(),
        displayLabel = "Legacy Vanilla Tub",
        syncStatus = SyncStatus.PENDING_UPLOAD.name,
        currentLocalHash = "legacy_hash"
      )
    )
    DatabaseIntegrityManager.closeSafely(legacySyncDb)

    val intruderAssessment = migrationManager.assessForUser(intruderUid)
    assertTrue(
      "Account mismatch must be blocked before migration",
      intruderAssessment is LegacyMigrationAssessment.OwnershipMismatchBlocked
    )
    val intruderAttempt = migrationManager.migrateLegacyToUser(intruderUid)
    assertFalse("Wrong account migration must fail", intruderAttempt.success)

    // Case 2: True owner assesses -> MATCHED_SYNC_METADATA -> confirms migration via SessionScopeManager
    val ownerAssessment = migrationManager.assessForUser(ownerUid)
    assertTrue(ownerAssessment is LegacyMigrationAssessment.RequiresOwnerConfirmation)
    assertEquals(
      LegacyOwnershipEvidence.MATCHED_SYNC_METADATA,
      (ownerAssessment as LegacyMigrationAssessment.RequiresOwnerConfirmation).evidence
    )

    val ownerAuthGateway = FakeAuthSessionGateway(
      initialUser = AuthUserDescriptor(ownerUid, "owner@icetracker.test", "Legacy Owner")
    )
    val manager = createManager(authGateway = ownerAuthGateway, markerStore = markerStore)
    val ownerDesc = AuthUserDescriptor(ownerUid, "owner@icetracker.test", "Legacy Owner")

    // Signing in triggers LegacyMigrationPrompt, NOT silent auto-import!
    manager.handleAuthUserChanged(ownerDesc)
    assertTrue(manager.sessionState.value is SessionState.LegacyMigrationPrompt)

    // Confirm owner migration
    val execResult = manager.confirmOwnerLegacyMigration(ownerDesc)
    assertTrue("Verified owner migration must succeed: ${execResult.errorMessage}", execResult.success)
    assertNotNull("Recoverable JSON backup file must be created before import", execResult.backupFilePath)
    assertTrue(File(execResult.backupFilePath!!).exists())

    // Legacy DB file must remain untouched on disk!
    assertTrue(
      "Original legacy database must NOT be deleted after migration",
      context.getDatabasePath(IceTrackDatabase.LEGACY_GLOBAL_DB_NAME).exists()
    )

    // Active session now holds migrated product + setting + sync record!
    val migratedSession = (manager.sessionState.value as SessionState.Active).session
    assertEquals(1, migratedSession.database.productDao().getAllIncludingArchivedSync().size)
    assertEquals("Legacy Creamery", migratedSession.database.appSettingDao().getSync("business_name")?.value)
    assertEquals(1, migratedSession.syncDatabase.syncStateDao().countByStatusSync(ownerUid, SyncStatus.PENDING_UPLOAD.name))

    // Case 3: Duplicate import and second-account claim must both be rejected
    val duplicateOwnerAttempt = migrationManager.migrateLegacyToUser(ownerUid)
    assertFalse("Duplicate import by same owner must be blocked", duplicateOwnerAttempt.success)

    val secondAccountClaimAttempt = migrationManager.migrateLegacyToUser(intruderUid)
    assertFalse("Second account claiming already-migrated legacy DB must be blocked", secondAccountClaimAttempt.success)
    assertEquals(
      LegacyMigrationAssessment.ClaimedByAnotherAccount,
      migrationManager.assessForUser(intruderUid)
    )
  }

  @Test
  fun legacyDatabaseStartFresh_defersMigrationAndOpensEmptyUidWorkspace() = runBlocking {
    val uid = "uid_fresh_start_user"
    openedUids.add(uid)
    val markerStore = SignOutMarkerStore(context)
    val legacyDb = IceTrackDatabase.openMigratedLegacyDatabase(context)
    ProductRepository(legacyDb).saveProductWithUnit(
      product = Product(
        name = "Unassigned Old Item",
        sku = "OLD-1",
        category = "Cones",
        defaultPurchasePricePaise = 500L,
        defaultSellingPricePaise = 900L,
        minStockLevelPieces = 1
      ),
      piecesPerBox = 10,
      boxesPerCarton = 1
    )
    DatabaseIntegrityManager.closeSafely(legacyDb)

    val user = AuthUserDescriptor(uid, "fresh@icetracker.test", "Fresh User")
    val manager = createManager(authGateway = FakeAuthSessionGateway(user), markerStore = markerStore)

    manager.handleAuthUserChanged(user)
    assertTrue(manager.sessionState.value is SessionState.LegacyMigrationPrompt)

    manager.startFreshWithoutLegacyMigration(user)
    val active = (manager.sessionState.value as SessionState.Active).session
    assertEquals(0, active.database.productDao().getAllIncludingArchivedSync().size)
    assertTrue(context.getDatabasePath(IceTrackDatabase.LEGACY_GLOBAL_DB_NAME).exists())
  }
}
