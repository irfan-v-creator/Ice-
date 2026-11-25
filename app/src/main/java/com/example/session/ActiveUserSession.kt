package com.example.session

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.data.local.IceTrackDatabase
import com.example.data.repository.AuditRepository
import com.example.data.repository.CreditDebitNoteRepository
import com.example.data.repository.CustomerRepository
import com.example.data.repository.ExpenseRepository
import com.example.data.repository.InventoryRepository
import com.example.data.repository.InvoiceLifecycleRepository
import com.example.data.repository.PaymentReminderRepository
import com.example.data.repository.ProductRepository
import com.example.data.repository.PurchaseRepository
import com.example.data.repository.RefundRepository
import com.example.data.repository.ReturnRepository
import com.example.data.repository.SalesRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SubledgerRepository
import com.example.data.repository.SupplierRepository
import com.example.data.sync.CloudSyncRepository
import com.example.data.sync.CloudSyncSafetyGate
import com.example.data.sync.CloudSyncSafetyPolicy
import com.example.data.sync.IceTrackSyncDatabase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin

/**
 * Isolated runtime container for one authenticated Firebase UID session.
 * Owns dedicated UID-scoped Room DBs, Repositories, ViewModelStoreOwner (covering all 13 ViewModels),
 * mutation gate, and CoroutineScope. Disposed cleanly on sign-out or account switch.
 */
class ActiveUserSession(
  val uid: String,
  val uidHash: String,
  val userEmail: String,
  val userDisplayName: String,
  val sessionToken: Long,
  val database: IceTrackDatabase,
  val syncDatabase: IceTrackSyncDatabase,
  val mutationGate: SessionMutationGate,
  val viewModelStore: ViewModelStore = ViewModelStore(),
  val sessionJob: Job = SupervisorJob(),
  appContext: Context,
  auth: FirebaseAuth,
  syncSafetyPolicy: CloudSyncSafetyPolicy = CloudSyncSafetyGate,
  val reconciliationRequired: Boolean = false
) {
  val sessionScope: CoroutineScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)

  val viewModelStoreOwner: ViewModelStoreOwner = object : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore
      get() = this@ActiveUserSession.viewModelStore
  }

  val productRepo = ProductRepository(database, mutationGate)
  val supplierRepo = SupplierRepository(database, mutationGate)
  val customerRepo = CustomerRepository(database, mutationGate)
  val subledgerRepo = SubledgerRepository(database, mutationGate)
  val settingsRepo = SettingsRepository(database, mutationGate)
  val inventoryRepo = InventoryRepository(database, mutationGate)
  val purchaseRepo = PurchaseRepository(database, mutationGate)
  val salesRepo = SalesRepository(database, mutationGate)
  val expenseRepo = ExpenseRepository(database, mutationGate)
  val reminderRepo = PaymentReminderRepository(database, mutationGate)
  val returnRepo = ReturnRepository(database, mutationGate)
  val noteRepo = CreditDebitNoteRepository(database, mutationGate)
  val refundRepo = RefundRepository(database, mutationGate)
  val auditRepo = AuditRepository(database, mutationGate)
  val lifecycleRepo = InvoiceLifecycleRepository(database, mutationGate)

  val cloudSyncRepo = CloudSyncRepository(
    context = appContext.applicationContext,
    localDb = database,
    syncDb = syncDatabase,
    auth = auth,
    syncGate = syncSafetyPolicy,
    mutationGate = mutationGate,
    isReconciliationRequired = { reconciliationRequired }
  )

  suspend fun cancelJobsAndClearViewModels() {
    runCatching { cloudSyncRepo.detachAllListeners() }
    viewModelStore.clear()
    sessionJob.cancelAndJoin()
  }
}
