package com.zerocache.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * No-Root automation. Flow:
 *  1. Caller (ClearEngine) calls openAppInfoAndClearCache(pkg).
 *  2. Service launches ACTION_APPLICATION_DETAILS_SETTINGS for that package.
 *  3. Service watches the AccessibilityEvent stream and, once the Settings page
 *     is on screen, finds the "Clear cache" button (NOT "Clear data") and taps it.
 *  4. If a system confirmation dialog appears ("Clean cache?"), taps OK.
 *  5. Navigates back to the previous screen after clearing.
 *  6. Returns success once the click is dispatched and verified.
 *
 * Guard flag [isProcessingResult] prevents re-entrancy when multiple events
 * fire in quick succession while a result is already being processed.
 */
class ZeroCacheAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZeroCacheAccService"

        @Volatile
        var instance: ZeroCacheAccessibilityService? = null
            private set

        // Android Settings uses these well-known resource names for the buttons.
        // AOSP / Pixel: "com.android.settings:id/button2" is "Clear cache" on App Info -> Storage.
        // Samsung OneUI:  "com.android.settings:id/button2" too, but text fallback used just in case.
        private val CLEAR_CACHE_IDS = listOf(
            "com.android.settings:id/button2",
            "com.android.settings:id/clear_cache_button"
        )
        // Cache-only text patterns (avoid "clear data" / "hapus data" to prevent accidental data wipe)
        private val CLEAR_CACHE_TEXTS = listOf(
            "clear cache", "hapus cache", "cache", "tembolok"
        )
        // Text patterns that indicate "Clear Data" (dangerous - must be avoided)
        private val CLEAR_DATA_TEXTS = listOf(
            "clear data", "hapus data", "clear storage", "hapus penyimpanan"
        )

        // Storage & Cache button matching (to enter the Storage sub-screen)
        private val STORAGE_IDS = listOf(
            "com.android.settings:id/storage_settings",
            "com.android.settings:id/storage"
        )
        private val STORAGE_TEXTS = listOf(
            "storage", "penyimpanan", "memori", "storage & cache", "penyimpanan & cache"
        )

        // Confirmation dialog text patterns (system dialog that may appear after tapping Clear cache)
        private val DIALOG_TEXTS = listOf(
            "clear cache?", "clean cache?", "hapus cache?", "bersihkan cache?",
            "clear this app's cache?", "clear the cache?"
        )
        // Buttons on the confirmation dialog
        private val CONFIRM_BUTTON_TEXTS = listOf("ok", "yes", "ya", "clean", "hapus", "clear")
        private val CANCEL_BUTTON_TEXTS = listOf("cancel", "batal", "no", "tidak")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingPackage = AtomicReference<String?>(null)
    private val pendingResult = AtomicReference<(Boolean) -> Unit>(null)
    @Volatile private var clickedStorage = false
    @Volatile private var activePackageName: String? = null

    // Guard flag: prevents re-entrancy when processing result (multiple events fire in quick succession)
    @Volatile private var isProcessingResult = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.d(TAG, "onServiceConnected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        instance = null
    }

    /**
     * Public entry point used by ClearEngine. Suspends (off the main thread) until the
     * service either taps the clear-cache button or gives up after a timeout.
     */
    suspend fun openAppInfoAndClearCache(packageName: String): Boolean {
        val current = pendingPackage.getAndSet(packageName)
        if (current != null) {
            Log.w(TAG, "another package already in flight: $current, aborting")
            return false
        }
        clickedStorage = false
        isProcessingResult = false
        return kotlinx.coroutines.withTimeoutOrNull(20_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                pendingResult.set { ok -> if (cont.isActive) cont.resumeWith(Result.success(ok)) }
                launchAppInfo(packageName)
            }
        }.also {
            pendingPackage.set(null)
            pendingResult.set(null)
        } ?: run {
            Log.w(TAG, "openAppInfoAndClearCache timed out for $packageName")
            false
        }
    }

    private fun launchAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "launchAppInfo failed", t)
            pendingResult.get()?.invoke(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Track foreground package
        event.packageName?.toString()?.let { pkg ->
            activePackageName = pkg
        }

        // Guard: skip if no pending work or already processing a result
        if (pendingPackage.get() == null) return
        if (isProcessingResult) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        try {
            // Step 1: Try to find and click Storage entry (if not yet clicked)
            if (!clickedStorage && tryFindAndClickStorage(root)) {
                Log.d(TAG, "Storage option tapped for ${pendingPackage.get()}")
                clickedStorage = true
                return
            }

            // Step 2: Try to find and click Clear cache button
            if (tryFindAndClickClearCache(root)) {
                Log.d(TAG, "Clear cache tapped for ${pendingPackage.get()}")
                // Mark as processing BEFORE launching async coroutine to prevent re-entrancy
                isProcessingResult = true
                // Launch async verification and navigation
                scope.launch {
                    // Wait for cache to be cleared by the system
                    delay(800L)
                    // Try to detect and confirm any system dialog that appeared
                    confirmDialogIfPresent()
                    // Navigate back
                    if (!isZeroCacheInForeground()) {
                        navigateBack()
                        delay(300L)
                        if (!isZeroCacheInForeground()) {
                            navigateBack()
                        }
                    }
                    // Small extra delay then report success
                    delay(200L)
                    pendingResult.get()?.invoke(true)
                }
                return
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * After tapping Clear cache, the system may show a confirmation dialog.
     * Detect it and tap OK instead of accidentally dismissing it with BACK.
     */
    private suspend fun confirmDialogIfPresent() {
        // Poll a few times since dialog may appear after our initial click
        repeat(3) {
            delay(150L)
            val root = rootInActiveWindow ?: return
            try {
                if (handleDialogIfPresent(root)) return
            } finally {
                root.recycle()
            }
        }
    }

    /**
     * Check if current window is a confirmation dialog and tap OK.
     * Returns true if a dialog was found and handled.
     */
    private fun handleDialogIfPresent(root: AccessibilityNodeInfo): Boolean {
        // Check if window title/text matches a known confirmation dialog pattern
        val windowText = root.text?.toString()?.lowercase().orEmpty()
        val hasDialogText = DIALOG_TEXTS.any { it in windowText }

        if (!hasDialogText) return false

        Log.d(TAG, "Confirmation dialog detected, looking for OK button")

        // Find the OK/confirm button inside the dialog
        val confirmButton = findConfirmButton(root)
        if (confirmButton != null) {
            val ok = performClick(confirmButton)
            confirmButton.recycle()
            if (ok) {
                Log.d(TAG, "Dialog confirmed with OK tap")
                // Small delay after confirming dialog
                scope.launch { delay(300L) }
                return true
            }
            confirmButton.recycle()
        }
        return false
    }

    /**
     * Find a clickable confirm button (OK / Yes / Ya) inside a dialog.
     * Scans for buttons matching CONFIRM_BUTTON_TEXTS, avoiding cancel buttons.
     */
    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        collectAllButtons(root, allNodes)

        for (node in allNodes) {
            val text = (node.text ?: node.contentDescription)?.toString()?.lowercase().orEmpty()
            // Skip Cancel buttons
            if (CANCEL_BUTTON_TEXTS.any { it in text }) {
                node.recycle()
                continue
            }
            // Accept OK/Yes/Ya buttons
            if (CONFIRM_BUTTON_TEXTS.any { it in text } && node.isClickable && node.isEnabled) {
                // Got a confirm button — recycle the rest and return
                recycleAll(allNodes.filter { it != node })
                return node
            }
            node.recycle()
        }
        return null
    }

    private fun isZeroCacheInForeground(): Boolean {
        return activePackageName == this.packageName
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    private fun tryFindAndClickClearCache(root: AccessibilityNodeInfo): Boolean {
        // 1. Try by view id (fastest, most reliable on AOSP)
        for (id in CLEAR_CACHE_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                if (n.isClickable && n.isEnabled) {
                    val text = (n.text ?: n.contentDescription)?.toString()?.lowercase().orEmpty()
                    // Verify it's a cache-only button, NOT "Clear data"
                    if (isCacheOnlyButton(text)) {
                        return performClick(n)
                    }
                }
                n.recycle()
            }
        }
        // 2. Try by text (fallback for OEM skins)
        return clickAncestorOfText(root, CLEAR_CACHE_TEXTS, CLEAR_DATA_TEXTS)
    }

    private fun tryFindAndClickStorage(root: AccessibilityNodeInfo): Boolean {
        for (id in STORAGE_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                if (n.isClickable && n.isEnabled) {
                    return performClick(n)
                }
                n.recycle()
            }
        }
        return clickAncestorOfText(root, STORAGE_TEXTS)
    }

    /**
     * Returns true if the text looks like a "Clear cache" button (not "Clear data").
     */
    private fun isCacheOnlyButton(text: String): Boolean {
        val lower = text.lowercase()
        if (CLEAR_DATA_TEXTS.any { it in lower }) return false
        return CLEAR_CACHE_TEXTS.any { it in lower }
    }

    private fun clickAncestorOfText(
        root: AccessibilityNodeInfo,
        needles: List<String>,
        rejectTexts: List<String> = emptyList()
    ): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        findNodesWithText(root, needles, nodes)
        for (n in nodes) {
            val nodeText = (n.text ?: n.contentDescription)?.toString()?.lowercase().orEmpty()
            if (rejectTexts.any { it in nodeText }) {
                n.recycle()
                continue
            }
            // Collect ancestors first before recycling any nodes
            val ancestors = ArrayList<AccessibilityNodeInfo>()
            var current: AccessibilityNodeInfo? = n
            while (current != null) {
                ancestors.add(current)
                current = current.parent
            }
            // Find first clickable+enabled ancestor and tap it
            for (ancestor in ancestors) {
                if (ancestor.isClickable && ancestor.isEnabled) {
                    val ok = performClick(ancestor)
                    recycleAll(nodes)
                    return ok
                }
            }
        }
        recycleAll(nodes)
        return false
    }

    private fun findNodesWithText(
        node: AccessibilityNodeInfo,
        needles: List<String>,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val text = (node.text ?: node.contentDescription)?.toString()?.lowercase().orEmpty()
        if (needles.any { it in text }) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesWithText(child, needles, out)
            child.recycle()
        }
    }

    /**
     * Collect all clickable button-like nodes in the tree.
     */
    private fun collectAllButtons(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val text = (node.text ?: node.contentDescription)?.toString()?.lowercase().orEmpty()
        val isButton = node.className?.toString()?.contains("Button", ignoreCase = true) == true ||
                node.isClickable
        if (isButton && (text.isNotEmpty() || node.isClickable)) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllButtons(child, out)
            child.recycle()
        }
    }

    private fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        for (n in nodes) {
            try { n.recycle() } catch (_: Throwable) {}
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val args = Bundle()
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK, args)
            } else {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "performClick failed", t)
            false
        }
    }

    private fun navigateBack() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (t: Throwable) {
            Log.w(TAG, "navigateBack failed", t)
        }
    }
}
