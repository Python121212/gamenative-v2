package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.service.drive.DriveStorageManager
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.utils.FormatUtils
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink

@Composable
fun SettingsGroupDriveStorage() {
    val context = LocalContext.current
    val manager = remember { DriveStorageManager.getInstance(context) }
    val store = remember { manager.store }

    var showDialog by rememberSaveable { mutableStateOf(false) }
    var accountCount by remember { mutableStateOf(0) }
    var usedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        manager.refresh()
        val quota = manager.totalQuota()
        accountCount = quota.accountCount
        usedBytes = quota.usedBytes
        totalBytes = quota.totalBytes
    }

    // Keep the subtitle in sync while the dialog is open.
    val entries by manager.entries.collectAsState()
    LaunchedEffect(entries) {
        val quota = store.totalQuota()
        accountCount = quota.accountCount
        usedBytes = quota.usedBytes
        totalBytes = quota.totalBytes
    }

    SettingsGroup {
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.settings_drive_storage_title)) },
            subtitle = {
                Text(
                    if (accountCount == 0) {
                        stringResource(R.string.settings_drive_storage_empty)
                    } else {
                        stringResource(
                            R.string.settings_drive_storage_summary,
                            accountCount,
                            FormatUtils.formatBytes(usedBytes),
                            FormatUtils.formatBytes(totalBytes),
                        )
                    },
                )
            },
            icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
            onClick = { showDialog = true },
        )
    }

    if (showDialog) {
        DriveStorageDialog(
            manager = manager,
            onDismiss = { showDialog = false },
        )
    }
}
