package com.zerocache.util

import java.util.Locale

/**
 * Convert raw bytes to a compact human-readable size string.
 * e.g. 512 -> "512 B", 1536 -> "1.5 KB", 5242880 -> "5.0 MB", 2147483648 -> "2.0 GB".
 */
object SizeFormatter {

    fun format(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatForLanguage(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format(locale, "%.1f KB", b / 1024.0)
            b < 1024 * 1024 * 1024 -> String.format(locale, "%.1f MB", b / (1024.0 * 1024.0))
            else -> String.format(locale, "%.1f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
