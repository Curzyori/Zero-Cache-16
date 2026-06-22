package com.zerocache.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.zerocache.service.ZeroCacheAccessibilityService
import com.zerocache.util.RootChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Outcome of a single clear-cache attempt.
 */
sealed class ClearResult {
    data class Success(val freedBytes: Long) : ClearResult()
    data class Failure(val reason: String) : ClearResult()
    data object Skipped : ClearResult()
}

/**
 * Strategy for clearing cache. The ViewModel picks the right one based on device capability
 * + user preference.
 */
sealed class ClearStrategy {
    data object Root : ClearStrategy()
    data object NoRoot : ClearStrategy()
    data object DirectApi : ClearStrategy()  // Hidden API via reflection
}

class ClearEngine(
    private val context: Context,
    private val scanner: AppCacheScanner
) {
    private val tag = "ClearEngine"

    /**
     * Try to clear cache directly using hidden PackageManager API via reflection.
     * This works on many Android versions (especially Android 6-10) without root.
     * On newer Android versions, this may be blocked due to hidden API restrictions.
     * Verifies actual freed bytes by scanning before and after.
     */
    suspend fun clearCacheDirect(info: AppCacheInfo): ClearResult = withContext(Dispatchers.IO) {
        val before = scanner.cacheSizeForPackage(info.packageName)
        try {
            val pm = context.packageManager
            val pmClass = pm.javaClass

            // Find the deleteApplicationCacheFiles method
            val deleteMethod = pmClass.methods.find { method ->
                method.name == "deleteApplicationCacheFiles" &&
                method.parameterTypes.size == 2
            }

            if (deleteMethod == null) {
                Log.w(tag, "deleteApplicationCacheFiles method not found")
                return@withContext ClearResult.Failure("method not available")
            }

            val latch = CountDownLatch(1)
            val successHolder = booleanArrayOf(false)

            // Get the IPackageDataObserver class via reflection
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")

            // Create a dynamic proxy for the observer
            val observer = Proxy.newProxyInstance(
                observerClass.classLoader,
                arrayOf(observerClass),
                InvocationHandler { _, method, args ->
                    if (method.name == "onRemoveCompleted" && args != null && args.size == 2) {
                        successHolder[0] = args[1] as? Boolean ?: false
                        latch.countDown()
                    }
                    null
                }
            )

            // Invoke deleteApplicationCacheFiles
            deleteMethod.invoke(pm, info.packageName, observer)

            // Wait up to 5 seconds for completion
            val completed = latch.await(5, TimeUnit.SECONDS)

            if (completed && successHolder[0]) {
                Log.d(tag, "Direct cache clear observer success for ${info.packageName}")
            }
        } catch (e: ClassNotFoundException) {
            Log.w(tag, "IPackageDataObserver class not available", e)
            return@withContext ClearResult.Failure("observer class not available")
        } catch (e: SecurityException) {
            Log.w(tag, "deleteApplicationCacheFiles permission denied", e)
            return@withContext ClearResult.Failure("permission denied")
        } catch (e: IllegalAccessException) {
            Log.w(tag, "deleteApplicationCacheFiles access denied", e)
            return@withContext ClearResult.Failure("access denied")
        } catch (t: Throwable) {
            Log.w(tag, "Direct cache clear failed for ${info.packageName}", t)
            return@withContext ClearResult.Failure(t.message ?: "unknown")
        }

        // Verify actual freed bytes by scanning after
        // Small delay to let filesystem sync
        delay(300L)
        val after = scanner.cacheSizeForPackage(info.packageName)
        val freed = (before - after).coerceAtLeast(0L)
        Log.d(tag, "Direct cache clear for ${info.packageName}: before=$before, after=$after, freed=$freed")

        if (freed > 0L) {
            ClearResult.Success(freed)
        } else {
            // Cache didn't shrink — API may be blocked on Android 11+ even if call succeeded
            ClearResult.Failure("cache unchanged — API blocked on this Android version")
        }
    }

    /**
     * Root mode: best-effort direct file removal of the app's cache dir + subdirs.
     * Uses `su` to elevate when needed.
     * Skips persistent system services (isClearable=false).
     * Captures before once and verifies after all roots are processed.
     */
    suspend fun clearCacheRoot(info: AppCacheInfo): ClearResult = withContext(Dispatchers.IO) {
        if (!RootChecker.isRooted()) {
            return@withContext ClearResult.Failure("root not available")
        }
        // Skip non-clearable persistent system services
        if (!info.isClearable) {
            Log.d(tag, "Skipping ${info.packageName}: not clearable (persistent system service)")
            return@withContext ClearResult.Skipped
        }

        val pkg = info.packageName
        val before = scanner.cacheSizeForPackage(pkg)

        val internalCache = File("/data/data/$pkg/cache")
        val externalCache = File(Environment.getExternalStorageDirectory(), "Android/data/$pkg/cache")
        val roots = listOf(internalCache, externalCache)

        var hadError = false
        for (root in roots) {
            if (!root.exists()) continue
            try {
                val ok = if (root.canWrite()) {
                    deleteRecursive(root)
                } else {
                    shellDelete(root.absolutePath)
                }
                if (!ok) hadError = true
            } catch (t: Throwable) {
                Log.w(tag, "delete failed for ${root.absolutePath}", t)
                hadError = true
            }
        }

        // Verify after all roots processed
        delay(300L)
        val after = scanner.cacheSizeForPackage(pkg)
        val freed = (before - after).coerceAtLeast(0L)
        Log.d(tag, "Root cache clear for $pkg: before=$before, after=$after, freed=$freed")

        if (hadError && freed == 0L) {
            ClearResult.Failure("delete failed")
        } else {
            ClearResult.Success(freed)
        }
    }

    private fun deleteRecursive(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isFile) return file.delete()
        val children = file.listFiles() ?: return true
        var ok = true
        for (child in children) ok = deleteRecursive(child) && ok
        return file.delete() && ok
    }

    private fun shellDelete(path: String): Boolean {
        return try {
            // Sanitize: reject paths with shell metacharacters
            val safe = path.replace(Regex("[;&|`\\$\\\\\\\"'<>]"), "")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf $safe"))
            proc.waitFor() == 0
        } catch (t: Throwable) {
            Log.w(tag, "shellDelete failed for $path", t)
            false
        }
    }

    /**
     * No-Root mode: rely on AccessibilityService to navigate to App Info -> Storage -> Clear cache.
     * Verifies actual freed bytes by scanning before and after the accessibility flow.
     */
    suspend fun clearCacheNoRoot(info: AppCacheInfo): ClearResult = withContext(Dispatchers.IO) {
        val service = ZeroCacheAccessibilityService.instance
            ?: return@withContext ClearResult.Failure("accessibility service not running")

        val before = scanner.cacheSizeForPackage(info.packageName)
        return@withContext try {
            val ok = service.openAppInfoAndClearCache(info.packageName)
            if (!ok) {
                return@withContext ClearResult.Failure("tap failed or timed out")
            }
            // Verify actual freed bytes
            delay(500L)
            val after = scanner.cacheSizeForPackage(info.packageName)
            val freed = (before - after).coerceAtLeast(0L)
            Log.d(tag, "NoRoot cache clear for ${info.packageName}: before=$before, after=$after, freed=$freed")
            if (freed > 0L) {
                ClearResult.Success(freed)
            } else {
                // Accessibility tapped but cache unchanged — dialog may have been cancelled
                ClearResult.Failure("cache unchanged — confirmation dialog may have been cancelled")
            }
        } catch (t: Throwable) {
            Log.w(tag, "clearCacheNoRoot failed", t)
            ClearResult.Failure(t.message ?: "unknown")
        }
    }

    /**
     * Convenience: run a clear over a list using the chosen strategy.
     * Reports progress through [onProgress].
     */
    suspend fun clearAll(
        items: List<AppCacheInfo>,
        strategy: ClearStrategy,
        onProgress: suspend (current: Int, total: Int, item: AppCacheInfo, result: ClearResult) -> Unit
    ) {
        val total = items.size
        for ((i, item) in items.withIndex()) {
            val result = when (strategy) {
                ClearStrategy.Root -> clearCacheRoot(item)
                ClearStrategy.NoRoot -> {
                    // Try direct API first (faster, no UI navigation)
                    val directResult = clearCacheDirect(item)
                    // Fallback to accessibility only if direct failed OR returned zero freed bytes
                    // (Android 11+ blocks the API even without throwing)
                    if (directResult is ClearResult.Success && directResult.freedBytes > 0) {
                        directResult
                    } else {
                        // Fall back to accessibility service
                        clearCacheNoRoot(item)
                    }
                }
                ClearStrategy.DirectApi -> clearCacheDirect(item)
            }
            onProgress(i + 1, total, item, result)
            if (strategy == ClearStrategy.NoRoot) {
                // Small delay between apps for accessibility service to settle
                delay(200L)
            }
        }
    }
}
