package ir.solina.fm

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationsManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persianDigitsLocalizesAsciiDigitsAndPreservesOtherCharacters() {
        assertEquals("حافظه ۱۲۳.۴ GB", persianDigits("حافظه 123.4 GB"))
    }

    @Test
    fun uniqueTargetReturnsOriginalNameWhenThereIsNoCollision() {
        val destination = temporaryFolder.newFolder("destination")
        val source = File(temporaryFolder.root, "report.pdf")

        assertEquals(File(destination, "report.pdf"), FileOperationsManager.getUniqueTarget(destination, source))
    }

    @Test
    fun uniqueTargetAddsCounterBeforeFileExtension() {
        val destination = temporaryFolder.newFolder("destination")
        File(destination, "report.pdf").writeText("existing")
        val source = File(temporaryFolder.root, "report.pdf")

        assertEquals(File(destination, "report (1).pdf"), FileOperationsManager.getUniqueTarget(destination, source))
    }

    @Test
    fun uniqueTargetSkipsNamesThatAlreadyExist() {
        val destination = temporaryFolder.newFolder("destination")
        File(destination, "report.txt").writeText("existing")
        File(destination, "report (1).txt").writeText("existing")
        val source = File(temporaryFolder.root, "report.txt")

        assertEquals(File(destination, "report (2).txt"), FileOperationsManager.getUniqueTarget(destination, source))
    }

    @Test
    fun uniqueTargetDoesNotAddExtensionToDirectoryNames() {
        val destination = temporaryFolder.newFolder("destination")
        File(destination, "Photos").mkdir()
        val source = temporaryFolder.newFolder("Photos")

        assertEquals(File(destination, "Photos (1)"), FileOperationsManager.getUniqueTarget(destination, source))
    }
}
