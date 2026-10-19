package ir.solina.fm

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Snapshot of storage capacity. Values are expressed in bytes.
 */
data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long
) {
    private val safeTotalBytes: Long
        get() = totalBytes.coerceAtLeast(0L)

    private val safeUsedBytes: Long
        get() = usedBytes.coerceIn(0L, safeTotalBytes)

    val freeBytes: Long
        get() = (safeTotalBytes - safeUsedBytes).coerceAtLeast(0L)

    /** Whole-number usage percentage in the inclusive range 0..100. */
    val usagePercent: Int
        get() = if (safeTotalBytes == 0L) 0
        else ((safeUsedBytes.toDouble() / safeTotalBytes.toDouble()) * 100.0)
            .toInt().coerceIn(0, 100)

    fun formattedUsedGb(): String = formatToPersianGb(safeUsedBytes)
    fun formattedTotalGb(): String = formatToPersianGb(safeTotalBytes)
    fun formattedFreeGb(): String = formatToPersianGb(freeBytes)

    private fun formatToPersianGb(bytes: Long): String {
        val gbValue = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val symbols = DecimalFormatSymbols(Locale.US)
        val formatter = DecimalFormat("#,##0.0", symbols)
        val latin = formatter.format(gbValue)
        val persian = latin.map { ch ->
            when (ch) {
                in '0'..'9' -> ('۰'.code + (ch - '0')).toChar()
                ',' -> '٬'
                '.' -> '٫'
                else -> ch
            }
        }.joinToString("")
        return "$persian گیگابایت"
    }
}
