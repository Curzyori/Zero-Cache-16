package com.zerocache.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

/**
 * Scans installed apps and reports their best-effort cache sizes.
 *
 * Strategy:
 *  1. Enumerate installed apps via PackageManager.getInstalledApplications().
 *  2. For each app, attempt to read the on-disk cache directory:
 *     - Internal: /data/data/<pkg>/cache (only readable by the system/root)
 *     - External: /storage/emulated/0/Android/data/<pkg>/cache (readable on all API levels)
 *  3. Sum the sizes. The result is conservative — internal caches are often invisible
 *     to non-system processes and will show as 0 until the user grants the
 *     PACKAGE_USAGE_STATS permission, after which we use StorageStatsManager.
 */
class AppCacheScanner(private val context: Context) {

    private val tag = "AppCacheScanner"

    fun scanInstalledApps(): List<ApplicationInfo> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (t: Throwable) {
            Log.e(tag, "scanInstalledApps failed", t)
            emptyList()
        }
    }

    /**
     * Best-effort cache size in bytes. Returns 0 if the directory is unreadable.
     */
    fun cacheSizeForPackage(packageName: String): Long {
        if (hasUsageStatsPermission()) {
            try {
                val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as android.app.usage.StorageStatsManager
                val stats = storageStatsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    packageName,
                    android.os.Process.myUserHandle()
                )
                return stats.cacheBytes
            } catch (e: Exception) {
                Log.e(tag, "Failed to query stats for $packageName using StorageStatsManager", e)
            }
        }

        // Fallback for older devices or if permission not granted
        // External cache is readable on all API levels (subject to scoped storage)
        val externalCache = context.externalCacheDir?.parentFile?.parentFile?.let { base ->
            File(base, packageName + "/cache")
        }
        val internalCache = File("/data/data/$packageName/cache")
        return sumDirBytes(listOfNotNull(externalCache, internalCache.takeIf { it.exists() }))
    }

    private fun sumDirBytes(roots: List<File>): Long {
        var total = 0L
        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue
            total += walkSize(root)
        }
        return total
    }

    private fun walkSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        if (!file.isDirectory) return 0L
        var total = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            total += walkSize(child)
            if (total < 0L) return Long.MAX_VALUE // overflow guard
        }
        return total
    }

    /**
     * Build a sorted list of AppCacheInfo (cache size desc, zero-byte entries last).
     */
    fun scan(): List<AppCacheInfo> {
        val pm = context.packageManager
        val apps = scanInstalledApps()
        val result = ArrayList<AppCacheInfo>()
        for (info in apps) {
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem) continue
            val cacheBytes = cacheSizeForPackage(info.packageName)
            result.add(AppCacheInfo.fromApplicationInfo(pm, info, cacheBytes))
        }
        result.sortWith(AppCacheInfoOrdering.BySizeDesc)
        return result
    }

    /**
     * Returns true if Usage Access is granted. Required to read internal cache size
     * via StorageStatsManager for non-system processes on Android 8+.
     */
    fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            Log.w(tag, "hasUsageStatsPermission check failed", t)
            false
        }
    }
}
