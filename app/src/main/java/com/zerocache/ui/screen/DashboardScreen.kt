package com.zerocache.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.zerocache.R
import com.zerocache.data.AppCacheInfo
import com.zerocache.data.ClearStrategy
import com.zerocache.ui.DashboardViewModel
import com.zerocache.util.LocaleManager
import com.zerocache.util.SizeFormatter

/**
 * Root dashboard. Single-screen architecture (no nav graph — keeps the app focused
 * on its single purpose: clean cache).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onLanguageToggle: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val errorNoAccessibility = stringResource(R.string.err_no_accessibility)
    val errorNothingToClear = stringResource(R.string.list_empty)
    val errorClearFailed = stringResource(R.string.err_clear_failed, "")
    val progressIdle = stringResource(R.string.progress_idle)

    // Polling for accessibility service state (it can be enabled/disabled outside the app).
    // Use repeatOnLifecycle so polling stops when the screen is off / app backgrounded.
    // Polling for permission states (accessibility & usage stats).
    // Use repeatOnLifecycle so polling stops when the screen is off / app backgrounded.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.checkPermissions()
                delay(1000L)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.dashboard_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.dashboard_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_cd),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            // Summary header card
            SummaryHeader(
                totalBytes = state.totalCacheBytes,
                appCount = state.apps.size,
                freedBytes = state.freedBytes,
                clearedCount = state.clearedCount,
                isLoading = state.isLoading
            )

            Spacer(Modifier.height(16.dp))

            // Permission / mode card
            ModeAndPermissionsCard(
                isRooted = state.isRooted,
                hasAccessibility = state.hasAccessibility,
                hasUsageStats = state.hasUsageStats,
                strategy = state.strategy,
                onToggleStrategy = { viewModel.toggleStrategy() },
                onOpenAccessibility = onOpenAccessibilitySettings,
                onOpenUsage = onOpenUsageSettings
            )

            Spacer(Modifier.height(20.dp))

            // Section title + rescan
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.list_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.refresh(state.hasAccessibility) }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dashboard_scan))
                }
            }

            Spacer(Modifier.height(8.dp))

            // App list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!state.hasUsageStats) {
                    PermissionRequiredState(
                        onOpenUsage = onOpenUsageSettings
                    )
                } else if (state.isLoading && state.apps.isEmpty()) {
                    LoadingState()
                } else if (state.apps.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(state.apps, key = { it.packageName }) { item ->
                            AppCacheRow(
                                item = item,
                                onClearOne = { viewModel.clearOne(item) }
                            )
                        }
                    }
                }

                // Progress overlay
                if (state.isClearing) {
                    ClearingProgress(
                        current = state.progressCurrent,
                        total = state.progressTotal
                    )
                }
            }

            // Action button placed inline at the bottom of the Column
            ClearAllButton(
                enabled = state.apps.any { it.cacheSizeBytes > 0L } && !state.isClearing,
                onClick = { viewModel.requestClearAll() }
            )
        }
    }

    if (state.showConfirm) {
        ConfirmClearAllDialog(
            count = state.apps.count { it.cacheSizeBytes > 0L || state.strategy == ClearStrategy.Root },
            isRoot = state.strategy == ClearStrategy.Root,
            onConfirm = { viewModel.confirmClearAll() },
            onDismiss = { viewModel.dismissConfirm() }
        )
    }

    // Snackbar-style message banner — the ViewModel sets `message` to a
    // status key; we resolve it to a translated string and show a Snackbar
    // with a 3s timeout (the previous code only cleared the field).
    val scope = rememberCoroutineScope()
    state.message?.let { msgKey ->
        LaunchedEffect(msgKey) {
            val text = when {
                msgKey == "accessibility_required" -> errorNoAccessibility
                msgKey == "nothing_to_clear" -> errorNothingToClear
                msgKey.startsWith("clear_failed:") -> {
                    val appName = msgKey.substringAfter("clear_failed:")
                    context.getString(R.string.err_clear_failed, appName)
                }
                msgKey == "done" -> {
                    val freedStr = SizeFormatter.format(state.freedBytes)
                    val failSuffix = if (state.failedCount > 0) {
                        " " + context.getString(R.string.progress_done_with_failures, state.failedCount)
                    } else ""
                    context.getString(R.string.progress_done, freedStr, state.clearedCount, failSuffix)
                }
                else -> msgKey
            }
            scope.launch {
                snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Short)
                viewModel.clearMessage()
            }
        }
    }
}

@Composable
private fun SummaryHeader(
    totalBytes: Long,
    appCount: Int,
    freedBytes: Long,
    clearedCount: Int,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.dashboard_total_cache),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isLoading) "…" else SizeFormatter.format(totalBytes),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatColumn(
                    label = stringResource(R.string.dashboard_apps_found),
                    value = "$appCount",
                    modifier = Modifier.weight(1f)
                )
                StatColumn(
                    label = stringResource(R.string.dashboard_total_freed),
                    value = "${clearedCount} · ${SizeFormatter.format(freedBytes)}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ModeAndPermissionsCard(
    isRooted: Boolean,
    hasAccessibility: Boolean,
    hasUsageStats: Boolean,
    strategy: ClearStrategy,
    onToggleStrategy: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenUsage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.mode_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ModeChip(
                    label = stringResource(R.string.mode_no_root),
                    active = strategy == ClearStrategy.NoRoot,
                    onClick = { if (strategy != ClearStrategy.NoRoot) onToggleStrategy() }
                )
                Spacer(Modifier.width(6.dp))
                ModeChip(
                    label = stringResource(R.string.mode_root),
                    active = strategy == ClearStrategy.Root,
                    onClick = { if (strategy != ClearStrategy.Root && isRooted) onToggleStrategy() },
                    enabled = isRooted
                )
            }
            Spacer(Modifier.height(12.dp))
            // Strategy description
            Text(
                text = if (strategy == ClearStrategy.Root)
                    stringResource(R.string.mode_root_desc)
                else
                    stringResource(R.string.mode_no_root_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))
            // Permission rows
            if (strategy == ClearStrategy.NoRoot) {
                PermissionRow(
                    label = stringResource(R.string.perm_accessibility_title),
                    granted = hasAccessibility,
                    actionLabel = stringResource(R.string.perm_accessibility_btn),
                    onClick = onOpenAccessibility
                )
                Spacer(Modifier.height(8.dp))
            }
            PermissionRow(
                label = stringResource(R.string.perm_usage_title),
                granted = hasUsageStats,
                actionLabel = stringResource(R.string.perm_usage_btn),
                onClick = onOpenUsage
            )
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val container = when {
        active -> MaterialTheme.colorScheme.primary
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val text = when {
        active -> MaterialTheme.colorScheme.onPrimary
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = text)
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = actionLabel) { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (granted) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AppCacheRow(
    item: AppCacheInfo,
    onClearOne: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon (try to load, fall back to placeholder)
        val icon = remember(item.packageName) {
            try {
                context.packageManager.getApplicationIcon(item.packageName)
            } catch (_: Throwable) { null }
        }
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.appName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.appName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = if (item.isSystem) stringResource(R.string.list_app_system) else stringResource(R.string.list_app_user),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Text(
            text = SizeFormatter.format(item.cacheSizeBytes),
            style = MaterialTheme.typography.titleSmall,
            color = if (item.cacheSizeBytes > 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontWeight = FontWeight.Medium
        )
        if (item.cacheSizeBytes > 0L && item.isClearable) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onClearOne,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(stringResource(R.string.action_clear_one), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.list_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.dashboard_scanning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ClearingProgress(current: Int, total: Int) {
    val pct = if (total == 0) 0f else current.toFloat() / total
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    progress = { pct },
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 4.dp
                )
                Spacer(Modifier.height(16.dp))
                // 3-dot loading animation
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) { index ->
                        val alpha by animateFloatAsState(
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = StartOffset(index * 200)
                            ),
                            label = "dot$index"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.progress_clearing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ClearAllButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    ) {
        Icon(imageVector = Icons.Filled.Bolt, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.action_clear_all),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ConfirmClearAllDialog(
    count: Int,
    isRoot: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_clear_title)) },
        text = {
            Text(
                if (isRoot) stringResource(R.string.confirm_root_clear_msg, count)
                else stringResource(R.string.confirm_clear_msg, count)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_clear_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun LanguageFlagToggle(
    currentLang: String,
    onToggle: (String) -> Unit
) {
    val target = if (currentLang == LocaleManager.LANG_INDONESIAN)
        LocaleManager.LANG_ENGLISH else LocaleManager.LANG_INDONESIAN
    val flagRes = if (currentLang == LocaleManager.LANG_INDONESIAN) R.drawable.flag_en else R.drawable.flag_id
    val label = if (currentLang == LocaleManager.LANG_INDONESIAN)
        stringResource(R.string.lang_en) else stringResource(R.string.lang_id)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { onToggle(target) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(flagRes),
            contentDescription = stringResource(R.string.lang_toggle_cd) + ": " + label,
            tint = Color.Unspecified,
            modifier = Modifier.size(28.dp)
        )
    }
}

// Helper extension for converting Drawable to Bitmap for Compose Image
private fun android.graphics.drawable.Drawable.toBitmap(): android.graphics.Bitmap {
    if (this is android.graphics.drawable.BitmapDrawable) return bitmap
    val w = intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = intrinsicHeight.takeIf { it > 0 } ?: 96
    val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    setBounds(0, 0, w, h)
    draw(android.graphics.Canvas(bm))
    return bm
}

@Composable
private fun PermissionRequiredState(
    onOpenUsage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.perm_usage_required),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenUsage,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(stringResource(R.string.perm_usage_btn))
        }
    }
}
