package ir.solina.fm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOperationsManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun uniqueTargetUsesOriginalNameWhenDestinationIsFree() {
        val destination = temporaryFolder.newFolder("destination")
        val source = File(temporaryFolder.root, "report.pdf").apply { writeText("source") }

        val target = FileOperationsManager.getUniqueTarget(destination, source)

        assertEquals(File(destination, "report.pdf"), target)
        assertFalse(target.exists())
    }

    @Test
    fun uniqueTargetAddsCounterBeforeExtensionOnCollision() {
        val destination = temporaryFolder.newFolder("destination")
        val source = File(temporaryFolder.root, "report.pdf").apply { writeText("source") }
        File(destination, "report.pdf").writeText("existing")

        val target = FileOperationsManager.getUniqueTarget(destination, source)

        assertEquals(File(destination, "report (1).pdf"), target)
        assertFalse(target.exists())
    }

    @Test
    fun uniqueTargetHandlesFilesWithoutExtension() {
        val destination = temporaryFolder.newFolder("destination")
        val source = File(temporaryFolder.root, "README").apply { writeText("source") }
        File(destination, "README").writeText("existing")

        val target = FileOperationsManager.getUniqueTarget(destination, source)

        assertEquals(File(destination, "README (1)"), target)
    }

    @Test
    fun uniqueTargetAddsCounterToDirectoryCollision() {
        val destination = temporaryFolder.newFolder("destination")
        val source = File(temporaryFolder.root, "photos").apply { mkdir() }
        File(destination, "photos").mkdir()

        val target = FileOperationsManager.getUniqueTarget(destination, source)

        assertEquals(File(destination, "photos (1)"), target)
    }
    @Test
    fun copyTransferCopiesContentIntoDestination() = runBlocking {
        val source = File(temporaryFolder.root, "source.txt").apply { writeText("hello") }
        val destination = temporaryFolder.newFolder("copy-destination")

        val result = FileOperationsManager.executeTransfer(
            sources = listOf(source),
            destinationDir = destination,
            isMove = false,
            onProgress = {}
        )

        assertEquals(1, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals("hello", File(destination, "source.txt").readText())
        assertTrue(source.exists())
    }

    @Test
    fun transferRejectsMissingDestination() = runBlocking {
        val source = File(temporaryFolder.root, "source.txt").apply { writeText("hello") }
        val missingDestination = File(temporaryFolder.root, "does-not-exist")

        val result = FileOperationsManager.executeTransfer(
            sources = listOf(source),
            destinationDir = missingDestination,
            isMove = false,
            onProgress = {}
        )

        assertEquals(0, result.successCount)
        assertEquals(1, result.failureCount)
        assertTrue(result.errors.first().contains("مقصد"))
        assertTrue(source.exists())
    }

}
