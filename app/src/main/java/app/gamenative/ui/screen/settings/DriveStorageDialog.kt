package app.gamenative.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.service.drive.DriveAccount
import app.gamenative.service.drive.DriveAuthManager
import app.gamenative.service.drive.DriveFileEntry
import app.gamenative.service.drive.DriveFileState
import app.gamenative.service.drive.DriveStorageManager
import app.gamenative.utils.FormatUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings dialog for the Google Drive pseudo-SSD.
 *
 * Lets the user register multiple Google accounts (15 GB each), watch the
 * sequential upload queue, and restore or delete stored files.
 */
@Composable
fun DriveStorageDialog(
    manager: DriveStorageManager,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val accounts by manager.accounts.collectAsState()
    val entries by manager.entries.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var autoUpload by remember { mutableStateOf(PrefManager.drivePseudoSsdEnabled) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (data == null) return@rememberLauncherForActivityResult
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .addOnSuccessListener { googleAccount ->
                scope.launch {
                    try {
                        val account = DriveAuthManager.accountFromSignIn(googleAccount)
                        manager.registerAccount(account)
                    } catch (e: Exception) {
                        Timber.w(e, "DriveStorageDialog: register account failed")
                    } finally {
                        busy = false
                    }
                }
            }
            .addOnFailureListener { e ->
                Timber.w(e, "DriveStorageDialog: sign-in failed")
                busy = false
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.drive_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.drive_auto_upload),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = autoUpload,
                        onCheckedChange = {
                            autoUpload = it
                            PrefManager.drivePseudoSsdEnabled = it
                        },
                    )
                }

                Text(
                    text = stringResource(
                        R.string.drive_total_summary,
                        accounts.size,
                        FormatUtils.formatBytes(accounts.sumOf { it.quotaUsedBytes }),
                        FormatUtils.formatBytes(accounts.sumOf { it.quotaTotalBytes }),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                val total = accounts.sumOf { it.quotaTotalBytes }
                val used = accounts.sumOf { it.quotaUsedBytes }
                LinearProgressIndicator(
                    progress = {
                        if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )

                // Accounts
                accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        onToggle = { enabled ->
                            scope.launch { manager.setAccountEnabled(account.id, enabled) }
                        },
                        onRemove = { scope.launch { manager.removeAccount(account.id) } },
                    )
                }

                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Sign out first so the picker allows another account.
                            DriveAuthManager.signOut(context)
                            try {
                                signInLauncher.launch(DriveAuthManager.signInIntent(context))
                            } catch (e: Exception) {
                                Timber.w(e, "DriveStorageDialog: could not launch sign-in")
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.drive_add_account))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Stored items
                Text(
                    text = stringResource(R.string.drive_files_title),
                    style = MaterialTheme.typography.titleSmall,
                )

                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.drive_files_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            FileRow(
                                entry = entry,
                                onDelete = { scope.launch { manager.delete(entry) } },
                                onRestore = { scope.launch { manager.restoreFile(entry, entry.localPath?.let { java.io.File(it) } ?: java.io.File(context.filesDir, entry.name)) } },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun AccountRow(
    account: DriveAccount,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CloudUpload, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.email,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.drive_account_used,
                    FormatUtils.formatBytes(account.quotaUsedBytes),
                    FormatUtils.formatBytes(account.quotaTotalBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.drive_remove_account))
        }
    }
}

@Composable
private fun FileRow(
    entry: DriveFileEntry,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (entry.state) {
                    DriveFileState.QUEUED -> stringResource(R.string.drive_state_queued)
                    DriveFileState.UPLOADING ->
                        "${stringResource(R.string.drive_state_uploading)} ${(entry.progress * 100).toInt()}%"
                    DriveFileState.STORED ->
                        "${stringResource(R.string.drive_state_stored)} · ${FormatUtils.formatBytes(entry.sizeBytes)}"
                    DriveFileState.FAILED ->
                        entry.errorMessage ?: stringResource(R.string.drive_state_failed)
                    DriveFileState.DELETED -> stringResource(R.string.drive_state_deleted)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.state == DriveFileState.STORED) {
            TextButton(onClick = onRestore) {
                Text(stringResource(R.string.drive_action_restore))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = null)
        }
    }
}
