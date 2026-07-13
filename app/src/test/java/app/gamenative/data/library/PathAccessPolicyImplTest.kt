package app.gamenative.data.library

import android.os.Environment
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Test

class PathAccessPolicyImplTest {
    @Test
    fun missingDirectoryIsRejected() {
        val root = Files.createTempDirectory("path-policy-missing").resolve("missing").toFile()
        val policy = policy(exists = false)

        assertThrows(IllegalArgumentException::class.java) {
            policy.requireAccessibleDirectory(root.path)
        }
    }

    @Test
    fun detachedVolumeIsRejectedBeforeFilesystemProbes() {
        val root = Files.createTempDirectory("path-policy-detached").toFile()
        val policy = policy(storageState = Environment.MEDIA_UNMOUNTED)

        assertThrows(IllegalArgumentException::class.java) {
            policy.requireAccessibleDirectory(root.path)
        }
    }

    @Test
    fun readOnlyDirectoryIsRejected() {
        val root = Files.createTempDirectory("path-policy-read-only").toFile()
        val policy = policy(writable = false)

        assertThrows(IllegalArgumentException::class.java) {
            policy.requireAccessibleDirectory(root.path)
        }
    }

    @Test
    fun mountedReadableWritableDirectoryIsAccepted() {
        val root = Files.createTempDirectory("path-policy-accepted").toFile()

        policy().requireAccessibleDirectory(root.path)
    }

    private fun policy(
        storageState: String = Environment.MEDIA_MOUNTED,
        exists: Boolean = true,
        writable: Boolean = true,
    ) = PathAccessPolicyImpl(
        hasBroadStorageAccess = { true },
        storageState = { storageState },
        exists = { exists },
        isDirectory = { true },
        isReadable = { true },
        isWritable = { writable },
    )
}
