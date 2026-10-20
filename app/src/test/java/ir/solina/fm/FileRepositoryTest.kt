package ir.solina.fm

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repository = FileRepository()

    private fun sourceFile(name: String = "sample.txt", content: String = "hello"): File =
        File(tempFolder.newFolder("source"), name).apply { writeText(content) }

    private fun newDestination(): File = tempFolder.newFolder("destination")

    @Test
    fun copyExistingFileCopiesContentsAndKeepsSource() {
        val source = sourceFile()
        val result = repository.copyFile(source.path, newDestination().path)

        assertTrue(result.isFile)
        assertEquals("hello", result.readText())
        assertTrue(source.exists())
    }

    @Test
    fun copyMissingSourceThrowsFileNotFoundException() {
        val missing = File(tempFolder.root, "missing.txt")

        try {
            repository.copyFile(missing.path, newDestination().path)
            throw AssertionError("Expected FileNotFoundException")
        } catch (_: FileNotFoundException) {
            // Expected.
        }
    }

    @Test
    fun copyDuplicateNameCreatesNumberedFileWithoutOverwritingExistingFile() {
        val source = sourceFile("report.pdf", "new")
        val destination = newDestination()
        File(destination, "report.pdf").writeText("old")
        File(destination, "report(1).pdf").writeText("older")

        val result = repository.copyFile(source.path, destination.path)

        assertEquals("report(2).pdf", result.name)
        assertEquals("new", result.readText())
        assertEquals("old", File(destination, "report.pdf").readText())
    }

    @Test
    fun moveExistingFileCreatesDestinationAndRemovesSource() {
        val source = sourceFile("data.json", "{}")

        val result = repository.moveFile(source.path, newDestination().path)

        assertTrue(result.isFile)
        assertEquals("{}", result.readText())
        assertFalse(source.exists())
    }

    @Test
    fun moveDuplicateNameKeepsBothVersions() {
        val source = sourceFile("data.txt", "source")
        val destination = newDestination()
        File(destination, "data.txt").writeText("existing")

        val result = repository.moveFile(source.path, destination.path)

        assertEquals("data(1).txt", result.name)
        assertEquals("source", result.readText())
        assertEquals("existing", File(destination, "data.txt").readText())
        assertFalse(source.exists())
    }

    @Test
    fun moveFallsBackToCopyThenDeleteWhenRenameFails() {
        val source = sourceFile("fallback.bin", "payload")
        val repository = object : FileRepository() {
            override fun renameEntry(source: File, destination: File): Boolean = false
        }

        val result = repository.moveFile(source.path, newDestination().path)

        assertEquals("payload", result.readText())
        assertFalse(source.exists())
    }

    @Test
    fun deniedSourceAccessThrowsIOExceptionWithoutChangingSource() {
        val source = sourceFile()
        val repository = object : FileRepository() {
            override fun ensureSourceReadable(source: File) {
                throw IOException("Permission denied")
            }
        }

        try {
            repository.moveFile(source.path, newDestination().path)
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            assertTrue(source.exists())
        }
    }

    @Test
    fun copyFailureSimulatingFullDiskCleansPartialDestinationAndKeepsSource() {
        val source = sourceFile("large.bin", "original")
        val destination = newDestination()
        val repository = object : FileRepository() {
            override fun copyEntry(source: File, destination: File) {
                destination.writeText("partial")
                throw IOException("No space left on device")
            }
        }

        try {
            repository.copyFile(source.path, destination.path)
            throw AssertionError("Expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("No space"))
            assertTrue(source.exists())
            assertFalse(File(destination, source.name).exists())
        }
    }

    @Test
    fun moveCopyFailureKeepsSourceAndDoesNotLeavePartialDestination() {
        val source = sourceFile("large.bin", "original")
        val destination = newDestination()
        val repository = object : FileRepository() {
            override fun renameEntry(source: File, destination: File): Boolean = false

            override fun copyEntry(source: File, destination: File) {
                destination.writeText("partial")
                throw IOException("No space left on device")
            }
        }

        try {
            repository.moveFile(source.path, destination.path)
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            assertTrue(source.exists())
            assertEquals("original", source.readText())
            assertFalse(File(destination, source.name).exists())
        }
    }

    @Test
    fun invalidDestinationThrowsIOException() {
        val source = sourceFile()
        val destinationIsFile = File(tempFolder.root, "not-a-directory").apply { writeText("x") }

        try {
            repository.copyFile(source.path, destinationIsFile.path)
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            assertTrue(source.exists())
        }
    }
}
