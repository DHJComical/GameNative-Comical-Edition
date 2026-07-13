package app.gamenative.data.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.nio.file.Files
import timber.log.Timber

/** Android implementation combining broad permission, mounted-volume, and direct I/O checks. */
class PathAccessPolicyImpl internal constructor(
    private val hasBroadStorageAccess: () -> Boolean,
    private val storageState: (File) -> String,
    private val exists: (File) -> Boolean,
    private val isDirectory: (File) -> Boolean,
    private val isReadable: (File) -> Boolean,
    private val isWritable: (File) -> Boolean,
) : PathAccessPolicy {
    /** Creates the production policy using Android permission state and direct JVM filesystem probes. */
    constructor(context: Context) : this(
        hasBroadStorageAccess = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
        },
        storageState = Environment::getExternalStorageState,
        exists = { root -> Files.exists(root.toPath()) },
        isDirectory = { root -> Files.isDirectory(root.toPath()) },
        isReadable = { root -> root.canRead() && Files.isReadable(root.toPath()) },
        isWritable = { root -> root.canWrite() && Files.isWritable(root.toPath()) },
    )

    /** Performs fresh checks so revoked permissions and detached volumes fail before file writes. */
    override fun requireAccessibleDirectory(rootPath: String) {
        val root = File(canonicalLibraryPath(rootPath))
        val failure = when {
            !hasBroadStorageAccess() -> "All-files access is required"
            storageState(root) != Environment.MEDIA_MOUNTED ->
                "Library storage volume is not mounted"
            !exists(root) -> "Library directory does not exist"
            !isDirectory(root) -> "Library path is not a directory"
            !isReadable(root) -> "Library directory is not readable"
            !isWritable(root) -> "Library directory is not writable"
            else -> null
        }
        if (failure != null) {
            val exception = IllegalArgumentException("$failure: ${root.path}")
            Timber.e(exception, "Custom library path access validation failed")
            throw exception
        }
    }

}
