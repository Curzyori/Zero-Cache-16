package com.zerocache.util

import java.util.Locale

/**
 * Convert raw bytes to a compact human-readable size string.
 * e.g. 1024 -> "1.0 KB", 5_242_880 -> "5.0 MB".
 */
object SizeFormatter {

    fun format(bytes: Long): String {
        val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    fun formatForLanguage(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
        return String.format(locale, "%.1f MB", mb)
    }
}
