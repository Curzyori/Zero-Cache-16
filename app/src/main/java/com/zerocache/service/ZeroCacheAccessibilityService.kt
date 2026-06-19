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
 *  4. Navigates back to the previous screen after clearing.
 *  5. Returns success once the click is dispatched.
 *
 * The class also exposes a static [instance] for the engine to talk to without
 * needing a binder round-trip.
 */
class ZeroCacheAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZeroCacheAccService"

        @Volatile
        var instance: ZeroCacheAccessibilityService? = null
            private set

        // Android Settings uses these well-known resource names for the buttons.
        // AOSP / Pixel: "com.android.settings:id/button2" is "Clear cache" on App Info → Storage.
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingPackage = AtomicReference<String?>(null)
    private val pendingResult = AtomicReference<(Boolean) -> Unit>(null)
    @Volatile
    private var clickedStorage = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            // Make sure we can retrieve window content
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
        clickedStorage = false // Reset state for new app
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
        if (pendingPackage.get() == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        try {
            if (tryFindAndClickClearCache(root)) {
                Log.d(TAG, "Clear cache tapped for ${pendingPackage.get()}")
                // Wait a bit for the cache to be cleared, then navigate back
                scope.launch {
                    delay(500L)
                    navigateBack()
                    if (clickedStorage) {
                        delay(350L)
                        navigateBack()
                    }
                    delay(300L)
                    pendingResult.get()?.invoke(true)
                }
            } else if (!clickedStorage && tryFindAndClickStorage(root)) {
                Log.d(TAG, "Storage option tapped for ${pendingPackage.get()}")
                clickedStorage = true
            }
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    private fun tryFindAndClickClearCache(root: AccessibilityNodeInfo): Boolean {
        // 1. Try by view id (fastest)
        for (id in CLEAR_CACHE_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                if (n.isClickable && n.isEnabled) {
                    val text = (n.text ?: n.contentDescription)?.toString()?.lowercase().orEmpty()
                    // Make sure it's a cache button, NOT a data button
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
        // 1. Try by view id (fastest)
        for (id in STORAGE_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                if (n.isClickable && n.isEnabled) {
                    return performClick(n)
                }
                n.recycle()
            }
        }
        // 2. Try by text
        return clickAncestorOfText(root, STORAGE_TEXTS)
    }

    /**
     * Returns true if the text looks like a "Clear cache" button (not "Clear data").
     */
    private fun isCacheOnlyButton(text: String): Boolean {
        val lower = text.lowercase()
        // Reject if it looks like "Clear data" / "Hapus data"
        if (CLEAR_DATA_TEXTS.any { it in lower }) return false
        // Accept if it contains cache-related keywords
        return CLEAR_CACHE_TEXTS.any { it in lower }
    }

    private fun clickAncestorOfText(root: AccessibilityNodeInfo, needles: List<String>, rejectTexts: List<String> = emptyList()): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        findNodesWithText(root, needles, nodes)
        for (n in nodes) {
            val nodeText = (n.text ?: n.contentDescription)?.toString()?.lowercase().orEmpty()
            if (rejectTexts.any { it in nodeText }) {
                continue
            }
            var current: AccessibilityNodeInfo? = n
            while (current != null) {
                if (current.isClickable && current.isEnabled) {
                    val ok = performClick(current)
                    recycleAll(nodes)
                    if (current != n) current.recycle()
                    return ok
                }
                val parent = current.parent
                if (current != n) current.recycle()
                current = parent
            }
        }
        recycleAll(nodes)
        return false
    }

    private fun findNodesWithText(node: AccessibilityNodeInfo, needles: List<String>, out: MutableList<AccessibilityNodeInfo>) {
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

    private fun collectClickableNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableNodes(child, out)
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

    /**
     * Navigate back to the previous screen (so the next app can be processed).
     */
    private fun navigateBack() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use gesture-based back navigation (API 24+)
                val path = Path().apply {
                    moveTo(100f, 100f)
                    lineTo(100f, 100f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                    .build()
                // Use ACTION_BACK if available
                performGlobalAction(GLOBAL_ACTION_BACK)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "navigateBack failed", t)
        }
    }
}
