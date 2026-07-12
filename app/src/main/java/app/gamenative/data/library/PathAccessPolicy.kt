package app.gamenative.data.library

/** Validates that a custom library root is currently usable for direct filesystem operations. */
interface PathAccessPolicy {
    /**
     * Rejects a root unless broad storage permission is granted, its volume is mounted, and the
     * existing directory is readable and writable at the time of the operation.
     */
    fun requireAccessibleDirectory(rootPath: String)
}
