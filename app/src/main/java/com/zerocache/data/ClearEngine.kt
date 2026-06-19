package com.zerocache.data

import android.content.Context
import android.os.Build
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
     */
    suspend fun clearCacheDirect(info: AppCacheInfo): ClearResult = withContext(Dispatchers.IO) {
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
            
            val before = scanner.cacheSizeForPackage(info.packageName)
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
                val after = scanner.cacheSizeForPackage(info.packageName)
                val freed = (before - after).coerceAtLeast(0L)
                Log.d(tag, "Direct cache clear success for ${info.packageName}, freed: $freed")
                return@withContext ClearResult.Success(freed)
            } else {
                Log.w(tag, "Direct cache clear failed or timed out for ${info.packageName}")
                // Even if observer reports failure, check if cache was actually cleared
                val after = scanner.cacheSizeForPackage(info.packageName)
                if (after < before) {
                    val freed = (before - after).coerceAtLeast(0L)
                    return@withContext ClearResult.Success(freed)
                }
                return@withContext ClearResult.Failure("observer reported failure or timed out")
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
    }

    /**
     * Root mode: best-effort direct file removal of the app's cache dir + subdirs.
     * Uses `su` to elevate when needed.
     */
    suspend fun clearCacheRoot(info: AppCacheInfo): ClearResult = withContext(Dispatchers.IO) {
        if (!RootChecker.isRooted()) {
            return@withContext ClearResult.Failure("root not available")
        }
        val pkg = info.packageName
        val roots = listOf(
            File("/data/data/$pkg/cache"),
            File("/sdcard/Android/data/$pkg/cache")
        )
        var freed = 0L
        var hadError = false
        for (root in roots) {
            if (!root.exists()) continue
            val before = scanner.cacheSizeForPackage(pkg)
            val ok = try {
                if (root.canWrite()) {
                    deleteRecursive(root)
                } else {
                    shellDelete(root.absolutePath)
                }
            } catch (t: Throwable) {
                Log.w(tag, "delete failed for ${root.absolutePath}", t)
                hadError = true
                false
            }
            if (ok) {
                val after = scanner.cacheSizeForPackage(pkg)
                freed += (before - after).coerceAtLeast(0L)
            }
        }
        // After deletion, also try pm trim cache (API 26+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pm = context.packageManager
                // PackageManager.freeStorage is deprecated and requires ALL_FILES, skip.
                // We rely on filesystem deletion instead.
            }
        } catch (_: Throwable) {}
        if (hadError && freed == 0L) ClearResult.Failure("delete failed") else ClearResult.Success(freed)
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
            val safe = path.replace(Regex("[;&|`\$\\\\\"'<>]"), "")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf $safe"))
            proc.waitFor() == 0
        } catch (t: Throwable) {
            Log.w(tag, "shellDelete failed for $path", t)
            false
        }
    }

    /**
     * No-Root mode: rely on AccessibilityService to navigate to App Info → Storage → Clear cache.
     */
    suspend fun clearCacheNoRoot(info: AppCacheInfo): ClearResult = withContext(Dispatchers.IO) {
        val service = ZeroCacheAccessibilityService.instance
            ?: return@withContext ClearResult.Failure("accessibility service not running")
        return@withContext try {
            val ok = service.openAppInfoAndClearCache(info.packageName)
            if (ok) ClearResult.Success(info.cacheSizeBytes) else ClearResult.Failure("tap failed")
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
                    if (directResult is ClearResult.Success) {
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

