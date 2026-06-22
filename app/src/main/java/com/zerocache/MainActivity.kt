package com.zerocache

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zerocache.ui.DashboardViewModel
import com.zerocache.ui.screen.DashboardScreen
import com.zerocache.ui.theme.ZeroCacheTheme
import com.zerocache.util.LocaleManager

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZeroCacheTheme {
                var showSettings by remember { mutableStateOf(false) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onLanguageToggle = { newLang ->
                            LocaleManager.setLanguage(this, newLang)
                            recreate()
                        },
                        onOpenAccessibilitySettings = {
                            com.zerocache.util.SettingsOpener.openAccessibilitySettings(this)
                        },
                        onOpenUsageSettings = {
                            com.zerocache.util.SettingsOpener.openUsageAccessSettings(this)
                        },
                        onOpenSettings = { showSettings = true }
                    )
                }

                if (showSettings) {
                    SettingsDialog(
                        currentLang = LocaleManager.getLanguage(this),
                        onDismiss = { showSettings = false },
                        onLanguageToggle = { newLang ->
                            LocaleManager.setLanguage(this, newLang)
                            recreate()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    currentLang: String,
    onDismiss: () -> Unit,
    onLanguageToggle: (String) -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_support),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Change Language option
                Text(
                    text = stringResource(R.string.settings_change_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            val newLang = if (currentLang == LocaleManager.LANG_INDONESIAN)
                                LocaleManager.LANG_ENGLISH else LocaleManager.LANG_INDONESIAN
                            onLanguageToggle(newLang)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (currentLang == LocaleManager.LANG_INDONESIAN)
                            stringResource(R.string.lang_en)
                        else
                            stringResource(R.string.lang_id),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.lang_toggle_cd),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider()

                // GitHub Repo
                Text(
                    text = stringResource(R.string.settings_github),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Curzyori/zero-cache"))
                            context.startActivity(intent)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Curzyori/zero-cache",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider()

                // Support / Donate
                Text(
                    text = stringResource(R.string.settings_donate_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.settings_donate_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                // EVM
                AddressRow(
                    label = stringResource(R.string.settings_evm_address),
                    address = "0x54e18F0345a099D9FE6dd0576bb1699733c44735",
                    onCopy = {
                        copyToClipboard(context, "0x54e18F0345a099D9FE6dd0576bb1699733c44735")
                    }
                )

                // BTC
                AddressRow(
                    label = stringResource(R.string.settings_btc_address),
                    address = "bc1q7g5whvwjvrh7mtuap2tu7qh3tyyhvls36cp7fs",
                    onCopy = {
                        copyToClipboard(context, "bc1q7g5whvwjvrh7mtuap2tu7qh3tyyhvls36cp7fs")
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    )
}

@Composable
private fun AddressRow(
    label: String,
    address: String,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("address", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, R.string.settings_address_copied, Toast.LENGTH_SHORT).show()
}
