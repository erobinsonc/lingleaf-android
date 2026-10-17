package app.lingleaf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun writesReplaceTheFileAndLeaveNoTempFile() {
        val storage = FileStorage(folder.root)
        storage.write("words.json", "old")
        storage.write("words.json", "new")
        assertEquals("new", storage.read("words.json"))
        assertEquals(listOf("words.json"), folder.root.list()!!.toList())
    }

    @Test
    fun aNameOutsideTheFolderIsNeverRead() {
        val inside = folder.newFolder("app")
        folder.root.resolve("outside.txt").writeText("outside")
        assertNull(FileStorage(inside).read("../outside.txt"))
    }

    @Test
    fun aFailedWriteDoesNotCrash() {
        val missing = folder.root.resolve("gone")
        // A folder that no longer exists, like a full disk, makes the write fail.
        FileStorage(missing).write("words.json", "new")
        assertNull(FileStorage(missing).read("words.json"))
    }
}
