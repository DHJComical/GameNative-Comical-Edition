package app.gamenative.data.library

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GameLibraryDisplayNameTest {
    @Test
    fun customLibraryUsesSelectedDirectoryName() {
        val parent = Files.createTempDirectory("library-display-name").toFile()
        val selected = File(parent, "My Games")

        try {
            assertEquals("My Games", customLibraryDisplayName(selected.path))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun filesystemRootUsesCanonicalRootPath() {
        val root = File.listRoots().first().canonicalFile

        assertEquals(root.path, customLibraryDisplayName(root.path))
    }

    @Test
    fun blankLibraryPathFailsFast() {
        assertThrows(IllegalArgumentException::class.java) {
            customLibraryDisplayName("   ")
        }
    }
}
