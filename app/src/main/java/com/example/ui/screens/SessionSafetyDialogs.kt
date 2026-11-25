package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.session.LegacyMigrationAssessment
import com.example.session.LegacyOwnershipEvidence
import com.example.session.SessionState
import com.example.session.SignOutFlowState
import com.example.session.SignOutUiPrompt
import com.example.ui.theme.FigmaAmber
import com.example.ui.theme.FigmaBackground
import com.example.ui.theme.FigmaInk
import com.example.ui.theme.FigmaLine
import com.example.ui.theme.FigmaMuted
import com.example.ui.theme.FigmaNavy
import com.example.ui.theme.FigmaRed
import com.example.ui.theme.FigmaSoftTeal
import com.example.ui.theme.FigmaTeal
import com.example.ui.theme.FigmaWhite

@Composable
fun SessionInitializingScreen(
  state: SessionState.Initializing
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(FigmaBackground)
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      CircularProgressIndicator(color = FigmaTeal)
      Text(
        text = "Opening isolated workspace...",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = FigmaInk
      )
      Text(
        text = state.email.ifBlank { state.uid },
        style = MaterialTheme.typography.bodySmall,
        color = FigmaMuted
      )
    }
  }
}

@Composable
fun SessionRecoveryRequiredScreen(
  state: SessionState.RecoveryRequired,
  onRetryRecovery: () -> Unit,
  onForceSignOut: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(FigmaBackground)
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = FigmaWhite),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 520.dp)
        .border(1.dp, FigmaRed.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
        .testTag("session_recovery_screen")
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Icon(Icons.Default.Warning, contentDescription = null, tint = FigmaRed)
          Text(
            text = "Interrupted Sign-Out Recovery Required",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = FigmaInk
          )
        }
        Text(
          text = "A previous sign-out or cloud persistence cleanup (${state.marker.phase.name}) did not finish cleanly. Local databases remain preserved and isolated.",
          style = MaterialTheme.typography.bodySmall,
          color = FigmaInk
        )
        Surface(
          color = FigmaRed.copy(alpha = 0.08f),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = state.errorMessage,
            fontSize = 12.sp,
            color = FigmaRed,
            modifier = Modifier.padding(10.dp)
          )
        }
        Button(
          onClick = onRetryRecovery,
          colors = ButtonDefaults.buttonColors(containerColor = FigmaTeal, contentColor = FigmaWhite),
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .testTag("retry_recovery_btn")
        ) {
          Text("Complete Recovery Cleanup", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
          onClick = onForceSignOut,
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
        ) {
          Text("Return to Sign In", color = FigmaNavy)
        }
      }
    }
  }
}

@Composable
fun SessionErrorScreen(
  state: SessionState.Error,
  onRetry: () -> Unit,
  onSignOut: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(FigmaBackground)
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = FigmaWhite),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 520.dp)
        .border(1.dp, FigmaRed.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Workspace Initialization Error",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = FigmaRed
        )
        Text(
          text = state.message,
          style = MaterialTheme.typography.bodySmall,
          color = FigmaInk
        )
        Button(
          onClick = onRetry,
          colors = ButtonDefaults.buttonColors(containerColor = FigmaTeal, contentColor = FigmaWhite),
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
        ) {
          Text("Retry")
        }
        OutlinedButton(
          onClick = onSignOut,
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
        ) {
          Text("Sign Out", color = FigmaNavy)
        }
      }
    }
  }
}

@Composable
fun LegacyMigrationPromptScreen(
  state: SessionState.LegacyMigrationPrompt,
  backupExportStatus: String?,
  onExportLegacyBackupToUri: (Uri) -> Unit,
  onConfirmMigrate: () -> Unit,
  onStartFresh: () -> Unit,
  onSignOut: () -> Unit
) {
  val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json")
  ) { uri ->
    if (uri != null) {
      onExportLegacyBackupToUri(uri)
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(FigmaBackground)
      .padding(20.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = FigmaWhite),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 560.dp)
        .border(1.dp, FigmaLine, RoundedCornerShape(14.dp))
        .testTag("legacy_migration_screen")
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(rememberScrollState())
          .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Icon(Icons.Default.Storage, contentDescription = null, tint = FigmaTeal)
          Text(
            text = "Existing Pre-Isolation Business Data Found",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = FigmaInk
          )
        }

        Text(
          text = "Signed in as: ${state.user.email.ifBlank { state.user.uid }}",
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.SemiBold,
          color = FigmaNavy
        )

        when (val assessment = state.assessment) {
          is LegacyMigrationAssessment.OwnershipMismatchBlocked -> {
            Surface(
              color = FigmaRed.copy(alpha = 0.08f),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, FigmaRed.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .testTag("legacy_ownership_blocked_banner")
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cross-Account Ownership Protection", fontWeight = FontWeight.Bold, color = FigmaRed, fontSize = 13.sp)
                Text(assessment.reason, fontSize = 12.sp, color = FigmaInk)
                Text(
                  "Records found: ${assessment.summary.productsCount} products, ${assessment.summary.customersCount} shops, ${assessment.summary.salesInvoicesCount} sales, ${assessment.summary.purchaseInvoicesCount} purchases.",
                  fontSize = 12.sp,
                  color = FigmaMuted
                )
              }
            }

            Button(
              onClick = onStartFresh,
              enabled = !state.isMigrating,
              colors = ButtonDefaults.buttonColors(containerColor = FigmaNavy, contentColor = FigmaWhite),
              modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("legacy_start_fresh_btn")
            ) {
              Text("Start Fresh Isolated Workspace for This Account")
            }

            OutlinedButton(
              onClick = onSignOut,
              enabled = !state.isMigrating,
              modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("legacy_switch_account_btn")
            ) {
              Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = FigmaRed)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Switch Google Account", color = FigmaRed)
            }
          }

          is LegacyMigrationAssessment.RequiresOwnerConfirmation -> {
            Surface(
              color = FigmaSoftTeal,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, FigmaTeal.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val ownershipNote = if (assessment.evidence == LegacyOwnershipEvidence.MATCHED_SYNC_METADATA) {
                  "Local sync history matches this Google account."
                } else {
                  "No cloud account was recorded on this offline database. Confirm explicitly whether this business data belongs to ${state.user.email.ifBlank { "your account" }}."
                }
                Text(ownershipNote, fontSize = 12.sp, color = FigmaInk)
                Text(
                  "Summary: ${assessment.summary.productsCount} products, ${assessment.summary.suppliersCount} suppliers, ${assessment.summary.customersCount} shops, ${assessment.summary.salesInvoicesCount} sales, ${assessment.summary.purchaseInvoicesCount} purchases, ${assessment.summary.subledgerEntriesCount} ledger entries.",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium,
                  color = FigmaNavy
                )
                Text(
                  "Legacy DB Integrity: ${if (assessment.legacyIntegrityReport.isHealthy) "Healthy" else assessment.legacyIntegrityReport.integrityMessage}",
                  fontSize = 11.sp,
                  color = FigmaMuted
                )
              }
            }

            Text(
              text = "The original legacy database will NEVER be deleted or overwritten automatically. A validated V5 JSON backup is saved before migration.",
              fontSize = 12.sp,
              color = FigmaMuted
            )

            backupExportStatus?.let { msg ->
              Surface(
                color = FigmaAmber.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(msg, fontSize = 12.sp, color = FigmaInk, modifier = Modifier.padding(10.dp))
              }
            }

            state.errorMessage?.let { err ->
              Surface(
                color = FigmaRed.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(err, fontSize = 12.sp, color = FigmaRed, modifier = Modifier.padding(10.dp))
              }
            }

            OutlinedButton(
              onClick = {
                exportLauncher.launch("icetrack-legacy-backup-${System.currentTimeMillis()}.json")
              },
              enabled = !state.isMigrating,
              modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("legacy_export_backup_btn")
            ) {
              Icon(Icons.Default.CloudDownload, contentDescription = null, tint = FigmaNavy)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Export Recoverable JSON Backup First", color = FigmaNavy)
            }

            Button(
              onClick = onConfirmMigrate,
              enabled = !state.isMigrating,
              colors = ButtonDefaults.buttonColors(containerColor = FigmaTeal, contentColor = FigmaWhite),
              modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("legacy_confirm_migrate_btn")
            ) {
              if (state.isMigrating) {
                CircularProgressIndicator(color = FigmaWhite, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Migrating & Verifying...")
              } else {
                Icon(Icons.Default.Security, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Confirm Owner & Migrate Data", fontWeight = FontWeight.SemiBold)
              }
            }

            OutlinedButton(
              onClick = onStartFresh,
              enabled = !state.isMigrating,
              modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("legacy_start_fresh_btn")
            ) {
              Text("Keep Legacy Untouched & Start Empty Workspace", color = FigmaInk)
            }

            TextButton(
              onClick = onSignOut,
              enabled = !state.isMigrating,
              modifier = Modifier.fillMaxWidth()
            ) {
              Text("Switch Google Account", color = FigmaRed)
            }
          }

          else -> Unit
        }
      }
    }
  }
}

@Composable
fun SignOutBlockingDialog(
  prompt: SignOutUiPrompt,
  onWaitRetry: () -> Unit,
  onCancelSignOut: () -> Unit,
  onConfirmDiscardAndSignOut: () -> Unit
) {
  AlertDialog(
    onDismissRequest = {
      if (prompt.state == SignOutFlowState.PENDING_WRITES_BLOCKED || prompt.state == SignOutFlowState.SIGNOUT_FAILED) {
        onCancelSignOut()
      }
    },
    properties = DialogProperties(
      dismissOnBackPress = prompt.state == SignOutFlowState.PENDING_WRITES_BLOCKED,
      dismissOnClickOutside = false
    ),
    title = {
      Text(
        text = when (prompt.state) {
          SignOutFlowState.SIGNOUT_CHECKING,
          SignOutFlowState.MUTATIONS_FROZEN,
          SignOutFlowState.WAITING_FOR_SAFE_SHUTDOWN,
          SignOutFlowState.DISCARD_AND_SIGNOUT_PENDING -> "Safely Signing Out..."
          SignOutFlowState.PENDING_WRITES_BLOCKED -> "Pending Cloud Writes Detected"
          SignOutFlowState.SIGNOUT_FAILED -> "Sign-Out Cleanup Paused"
          SignOutFlowState.SIGNOUT_COMPLETED,
          SignOutFlowState.IDLE -> "Sign Out"
        },
        fontWeight = FontWeight.Bold,
        color = if (prompt.state == SignOutFlowState.PENDING_WRITES_BLOCKED || prompt.state == SignOutFlowState.SIGNOUT_FAILED) {
          FigmaRed
        } else {
          FigmaInk
        }
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (prompt.state) {
          SignOutFlowState.SIGNOUT_CHECKING,
          SignOutFlowState.MUTATIONS_FROZEN,
          SignOutFlowState.WAITING_FOR_SAFE_SHUTDOWN,
          SignOutFlowState.DISCARD_AND_SIGNOUT_PENDING -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              CircularProgressIndicator(color = FigmaTeal, modifier = Modifier.size(24.dp))
              Text(prompt.statusMessage, fontSize = 13.sp, color = FigmaInk)
            }
          }

          SignOutFlowState.PENDING_WRITES_BLOCKED -> {
            Text(
              text = prompt.statusMessage.ifBlank {
                "Cloud write acknowledgement timed out or device is offline. Pending local records (${prompt.pendingLocalSyncCount}) remain safely saved in Room."
              },
              fontSize = 13.sp,
              color = FigmaInk
            )
            prompt.errorMessage?.let { err ->
              Text(err, fontSize = 12.sp, color = FigmaMuted)
            }
            Text(
              text = "Choose Wait to retry acknowledgement, Cancel to keep working, or Discard Pending Firestore Writes to clear only unacknowledged cloud cache (local Room data & sync queue stay preserved with reconciliationRequired).",
              fontSize = 12.sp,
              color = FigmaNavy
            )
          }

          SignOutFlowState.SIGNOUT_FAILED -> {
            Text(
              text = prompt.errorMessage ?: "Cleanup failed before sign-out could complete safely.",
              fontSize = 13.sp,
              color = FigmaRed
            )
          }

          else -> Unit
        }
      }
    },
    confirmButton = {
      when (prompt.state) {
        SignOutFlowState.PENDING_WRITES_BLOCKED -> {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = onWaitRetry,
              colors = ButtonDefaults.buttonColors(containerColor = FigmaTeal, contentColor = FigmaWhite),
              modifier = Modifier.testTag("signout_wait_btn")
            ) {
              Text("Wait")
            }
            Button(
              onClick = onConfirmDiscardAndSignOut,
              colors = ButtonDefaults.buttonColors(containerColor = FigmaRed, contentColor = FigmaWhite),
              modifier = Modifier.testTag("signout_discard_btn")
            ) {
              Text("Discard Pending Writes")
            }
          }
        }

        SignOutFlowState.SIGNOUT_FAILED -> {
          Button(
            onClick = onWaitRetry,
            colors = ButtonDefaults.buttonColors(containerColor = FigmaTeal, contentColor = FigmaWhite)
          ) {
            Text("Retry Sign-Out")
          }
        }

        else -> Unit
      }
    },
    dismissButton = {
      if (prompt.state == SignOutFlowState.PENDING_WRITES_BLOCKED || prompt.state == SignOutFlowState.SIGNOUT_FAILED) {
        TextButton(
          onClick = onCancelSignOut,
          modifier = Modifier.testTag("signout_cancel_btn")
        ) {
          Text("Cancel Sign-Out", color = FigmaMuted)
        }
      }
    }
  )
}
