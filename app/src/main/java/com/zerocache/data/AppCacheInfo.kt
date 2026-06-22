package com.zerocache.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Domain model for a single app + its detected cache size.
 *
 * @param packageName Android package name (e.g. com.whatsapp)
 * @param appName User-facing app name (e.g. "WhatsApp")
 * @param cacheSizeBytes Best-effort cache size in bytes (0 if unknown)
 * @param isSystem True if this is a system/preinstalled app
 * @param isClearable True if user/system policy allows this app's cache to be cleared
 */
data class AppCacheInfo(
    val packageName: String,
    val appName: String,
    val cacheSizeBytes: Long,
    val isSystem: Boolean,
    val isClearable: Boolean
) {
    companion object {
        fun fromApplicationInfo(
            pm: PackageManager,
            info: ApplicationInfo,
            cacheSizeBytes: Long
        ): AppCacheInfo {
            val appName = pm.getApplicationLabel(info).toString()
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            // isClearable: only persistent system services cannot have their cache cleared
            // FLAG_PERSISTENT = system component that runs always (cannot clear cache)
            // User apps and updated system apps are clearable
            val isPersistent = (info.flags and ApplicationInfo.FLAG_PERSISTENT) != 0
            val isClearable = !isSystem || !isPersistent
            return AppCacheInfo(
                packageName = info.packageName,
                appName = appName,
                cacheSizeBytes = cacheSizeBytes,
                isSystem = isSystem,
                isClearable = isClearable
            )
        }
    }
}

object AppCacheInfoOrdering {
    val BySizeDesc: Comparator<AppCacheInfo> = compareByDescending { it.cacheSizeBytes }
    val ByName: Comparator<AppCacheInfo> = compareBy { it.appName.lowercase() }
}
