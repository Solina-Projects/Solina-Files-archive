package ir.solina.fm

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val engine = SearchEngine()

    @Test
    fun searchesCaseInsensitivelyAcrossSubfolders() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        File(root, "Annual-Report.PDF").writeText("report")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "report-notes.txt").writeText("notes")
        File(nested, "other.txt").writeText("other")

        val results = engine.search(root.absolutePath, SearchCriteria(queryText = "REPORT"))

        assertEquals(setOf("Annual-Report.PDF", "report-notes.txt"), results.map { it.name }.toSet())
    }

    @Test
    fun filtersByFileType() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        File(root, "photo.JPG").writeText("image")
        File(root, "audio.mp3").writeText("audio")
        File(root, "installer.apk").writeText("apk")

        val results = engine.search(
            root.absolutePath,
            SearchCriteria(fileTypes = setOf(FileType.IMAGE, FileType.APK))
        )

        assertEquals(setOf("photo.JPG", "installer.apk"), results.map { it.name }.toSet())
    }

    @Test
    fun filtersBySizeAndModificationDate() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        val smallRecent = File(root, "small.txt").apply { writeText("tiny") }
        val largeRecent = File(root, "large.txt").apply { writeBytes(ByteArray(32)) }
        val oldLarge = File(root, "old.txt").apply {
            writeBytes(ByteArray(32))
            setLastModified(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000)
        }
        val results = engine.search(
            root.absolutePath,
            SearchCriteria(
                minSizeBytes = 16L,
                modifiedSinceDays = 7
            )
        )

        assertEquals(listOf(largeRecent.absolutePath), results.map { it.absolutePath })
        assertTrue(smallRecent.exists())
        assertTrue(oldLarge.exists())
    }

    @Test
    fun canLimitSearchToRootDirectory() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        File(root, "root.txt").writeText("root")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "nested.txt").writeText("nested")

        val results = engine.search(
            root.absolutePath,
            SearchCriteria(queryText = ".txt", searchInSubfolders = false)
        )

        assertEquals(listOf("root.txt"), results.map { it.name })
    }

    @Test
    fun emptyCriteriaReturnsNoResults() = runBlocking {
        val root = temporaryFolder.newFolder("root")
        File(root, "file.txt").writeText("content")

        assertTrue(engine.search(root.absolutePath, SearchCriteria()).isEmpty())
    }
}
