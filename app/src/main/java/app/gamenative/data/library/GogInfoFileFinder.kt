package app.gamenative.data.library

import java.io.File

/** Finds supported GOG info metadata within one exact game installation directory. */
interface GogInfoFileFinder {
    /**
     * Returns canonical root or nested info files up to the supported depth without following a
     * filesystem alias outside [installDirectory].
     */
    fun find(installDirectory: File): List<File>
}

/** Bounded canonical-filesystem implementation shared by GOG discovery and root resolution. */
class GogInfoFileFinderImpl : GogInfoFileFinder {
    override fun find(installDirectory: File): List<File> {
        val root = installDirectory.canonicalFile
        val rootPath = root.toPath()
        val visitedDirectories = mutableSetOf<String>()
        val infoFiles = mutableListOf<File>()

        fun visit(directory: File, depth: Int) {
            val canonicalDirectory = directory.canonicalFile
            if (!canonicalDirectory.toPath().startsWith(rootPath)) return
            if (!visitedDirectories.add(canonicalDirectory.path)) return

            canonicalDirectory.listFiles()?.sortedBy(File::getName)?.forEach { child ->
                val canonicalChild = child.canonicalFile
                if (!canonicalChild.toPath().startsWith(rootPath)) return@forEach
                when {
                    canonicalChild.isFile && isGogInfo(canonicalChild) -> infoFiles += canonicalChild
                    canonicalChild.isDirectory && depth < MAX_SEARCH_DEPTH -> visit(canonicalChild, depth + 1)
                }
            }
        }

        visit(root, 0)
        return infoFiles.distinctBy(File::getPath).sortedBy(File::getPath)
    }

    private fun isGogInfo(file: File): Boolean =
        file.name.startsWith(GOG_INFO_PREFIX) && file.name.endsWith(GOG_INFO_SUFFIX)

    private companion object {
        const val GOG_INFO_PREFIX = "goggame-"
        const val GOG_INFO_SUFFIX = ".info"
        const val MAX_SEARCH_DEPTH = 3
    }
}
