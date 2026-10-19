package ir.solina.fm

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageInfoTest {
    @Test
    fun calculatesUsageAndFreeBytes() {
        val info = StorageInfo(totalBytes = 100L, usedBytes = 75L)
        assertEquals(25L, info.freeBytes)
        assertEquals(75, info.usagePercent)
    }

    @Test
    fun clampsInvalidValuesToValidCapacityRange() {
        assertEquals(0, StorageInfo(totalBytes = 0L, usedBytes = 20L).usagePercent)
        assertEquals(100L, StorageInfo(totalBytes = 100L, usedBytes = -10L).freeBytes)
        assertEquals(0, StorageInfo(totalBytes = 100L, usedBytes = -10L).usagePercent)
        assertEquals(100, StorageInfo(totalBytes = 100L, usedBytes = 120L).usagePercent)
        assertEquals(0L, StorageInfo(totalBytes = -1L, usedBytes = 20L).freeBytes)
    }

    @Test
    fun formatsGbWithPersianDigitsAndSeparators() {
        val oneGb = 1024L * 1024L * 1024L
        assertEquals("۱٫۰ گیگابایت", StorageInfo(oneGb, oneGb).formattedUsedGb())
        assertEquals("۲٫۰ گیگابایت", StorageInfo(3L * oneGb, oneGb).formattedFreeGb())
        assertEquals("۳٫۰ گیگابایت", StorageInfo(3L * oneGb, oneGb).formattedTotalGb())
    }
}
