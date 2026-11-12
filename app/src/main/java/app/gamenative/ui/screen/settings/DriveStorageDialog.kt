package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import app.gamenative.service.drive.DriveFileEntry
import app.gamenative.service.drive.DriveFileState
import app.gamenative.service.drive.DriveOAuthClient
import app.gamenative.service.drive.DriveStorageManager
import app.gamenative.service.drive.toDriveAccount
import app.gamenative.utils.FormatUtils
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings dialog for the Google Drive pseudo-SSD.
 *
 * Adding an account is a single tap: the Google account chooser opens in the
 * browser and the user just picks an account (OAuth + PKCE, loopback redirect).
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
    var error by remember { mutableStateOf<String?>(null) }
    var autoUpload by remember { mutableStateOf(PrefManager.drivePseudoSsdEnabled) }

    var clientId by remember { mutableStateOf(DriveOAuthClient.clientId()) }
    var clientSecret by remember { mutableStateOf(DriveOAuthClient.clientSecret()) }
    var configured by remember { mutableStateOf(DriveOAuthClient.isConfigured()) }
    var showConfig by remember { mutableStateOf(!configured) }
    val configRequiredMessage = stringResource(R.string.drive_config_required)

    fun startSignIn() {
        error = null
        busy = true
        scope.launch {
            try {
                val tokens = DriveOAuthClient.signIn(context)
                manager.registerAccount(tokens.toDriveAccount())
            } catch (e: Exception) {
                Timber.w(e, "DriveStorageDialog: sign-in failed")
                error = e.message ?: e::class.java.simpleName
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
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
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.drive_auto_upload),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoUpload,
                        onCheckedChange = {
                            autoUpload = it
                            PrefManager.drivePseudoSsdEnabled = it
                        },
                    )
                }

                // --- Sign-in -------------------------------------------------
                if (busy) {
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.drive_login_running),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            if (configured) {
                                startSignIn()
                            } else {
                                showConfig = true
                                error = configRequiredMessage
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.drive_add_account))
                    }
                }

                error?.let { message ->
                    Text(
                        text = stringResource(R.string.drive_login_failed, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // --- OAuth client (only needed when not baked into the build) --
                if (showConfig && !busy) {
                    Text(
                        text = stringResource(R.string.drive_config_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text(stringResource(R.string.drive_config_client_id)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text(stringResource(R.string.drive_config_client_secret)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                    TextButton(
                        onClick = {
                            DriveOAuthClient.saveConfig(clientId, clientSecret)
                            configured = DriveOAuthClient.isConfigured()
                            if (configured) {
                                showConfig = false
                                startSignIn()
                            } else {
                                error = null
                            }
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.drive_config_save))
                    }
                } else if (configured) {
                    TextButton(onClick = { showConfig = !showConfig }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.drive_config_edit))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Quota ---------------------------------------------------
                val total = accounts.sumOf { it.quotaTotalBytes }
                val used = accounts.sumOf { it.quotaUsedBytes }
                Text(
                    text = stringResource(
                        R.string.drive_total_summary,
                        accounts.size,
                        FormatUtils.formatBytes(used),
                        FormatUtils.formatBytes(total),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                LinearProgressIndicator(
                    progress = {
                        if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )

                accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        onToggle = { enabled ->
                            scope.launch { manager.setAccountEnabled(account.id, enabled) }
                        },
                        onRemove = { scope.launch { manager.removeAccount(account.id) } },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Stored items --------------------------------------------
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
                            .heightIn(max = 200.dp)
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            FileRow(
                                entry = entry,
                                onDelete = { scope.launch { manager.delete(entry) } },
                                onRestore = {
                                    scope.launch {
                                        val target = entry.localPath?.let { java.io.File(it) }
                                            ?: java.io.File(context.filesDir, entry.name)
                                        manager.restoreFile(entry, target)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
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
        Switch(checked = account.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = null)
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
