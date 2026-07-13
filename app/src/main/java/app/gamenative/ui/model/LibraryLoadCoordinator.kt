package app.gamenative.ui.model

/** Local data sources that must emit before the first library snapshot can be displayed. */
internal enum class LibraryLoadSource {
    STEAM,
    GOG,
    EPIC,
    AMAZON,
    PLAY_HISTORY,
}

internal fun shouldProcessLibrarySourceEmission(hasChanges: Boolean, initialSnapshotReady: Boolean): Boolean =
    hasChanges || !initialSnapshotReady

/** Describes how a completed filter run may affect the library viewport. */
internal enum class FilterCommitKind {
    STRUCTURAL,
    EXPLICIT,
    PAGINATION,
    METADATA,
}

private enum class ViewportResetOrigin {
    AUTO_BOOTSTRAP,
    EXPLICIT,
}

/** Coordinates the initial local snapshot and rejects obsolete filtering results. */
internal class LibraryLoadCoordinator {
    private val readySources = mutableSetOf<LibraryLoadSource>()
    private var initialSnapshotReleased = false
    private var currentFilterRun = 0L
    private var explicitResetOwnerRun: Long? = null
    private var structuralResetOwnerRun: Long? = null
    private var viewportResetToken = 0L
    private var consumedViewportResetToken = 0L
    private var pendingViewportResetOrigin: ViewportResetOrigin? = null
    private var bootstrapViewportStabilizationActive = true
    private var committedAppIdOrder: List<String>? = null
    private var currentCommitKind = FilterCommitKind.STRUCTURAL

    /** Whether every required local source has emitted at least once. */
    val isInitialSnapshotReady: Boolean
        @Synchronized get() = readySources.size == LibraryLoadSource.entries.size

    /** Records a source emission and returns true exactly once when the initial snapshot is complete. */
    @Synchronized
    fun markReady(source: LibraryLoadSource): Boolean {
        readySources += source
        if (initialSnapshotReleased || readySources.size != LibraryLoadSource.entries.size) {
            return false
        }
        initialSnapshotReleased = true
        return true
    }

    /** Starts a filter run and returns its monotonically increasing identity. */
    @Synchronized
    fun beginFilterRun(commitKind: FilterCommitKind = FilterCommitKind.STRUCTURAL): Long {
        currentFilterRun += 1
        if (commitKind == FilterCommitKind.EXPLICIT) {
            explicitResetOwnerRun = currentFilterRun
        } else if (commitKind == FilterCommitKind.STRUCTURAL) {
            structuralResetOwnerRun = currentFilterRun
        }
        currentCommitKind = commitKind
        return currentFilterRun
    }

    /** Returns whether a filter run is still allowed to publish its result. */
    @Synchronized
    fun isCurrentFilterRun(run: Long): Boolean = run == currentFilterRun

    /**
     * Runs [commit] atomically when [run] is current and supplies any pending reset token.
     * While bootstrap stabilization is active, a changed item order also requests a reset.
     */
    @Synchronized
    fun commitIfCurrent(
        run: Long,
        appIdOrder: List<String>? = null,
        commit: (Long?) -> Unit,
    ): Boolean {
        if (run != currentFilterRun) return false
        val appIdOrderChanged = appIdOrder != null && appIdOrder != committedAppIdOrder
        val resetOrigin = when {
            explicitResetOwnerRun != null -> ViewportResetOrigin.EXPLICIT
            (structuralResetOwnerRun != null || currentCommitKind == FilterCommitKind.STRUCTURAL) &&
                bootstrapViewportStabilizationActive && appIdOrderChanged -> {
                ViewportResetOrigin.AUTO_BOOTSTRAP
            }
            else -> null
        }
        val resetToken = if (resetOrigin != null) {
            explicitResetOwnerRun = null
            structuralResetOwnerRun = null
            viewportResetToken += 1
            pendingViewportResetOrigin = resetOrigin
            viewportResetToken
        } else {
            structuralResetOwnerRun = null
            null
        }
        if (appIdOrder != null) {
            committedAppIdOrder = appIdOrder.toList()
        }
        commit(resetToken)
        return true
    }

    /** Stops automatic bootstrap resets after the user starts navigating the library. */
    @Synchronized
    fun stopBootstrapViewportStabilization() {
        bootstrapViewportStabilizationActive = false
        structuralResetOwnerRun = null
        if (pendingViewportResetOrigin == ViewportResetOrigin.AUTO_BOOTSTRAP) {
            pendingViewportResetOrigin = null
        }
    }

    /** Runs [action] atomically only while [run] remains current. */
    @Synchronized
    fun runIfCurrent(run: Long, action: () -> Unit): Boolean {
        if (run != currentFilterRun) return false
        action()
        return true
    }

    /** Clears a failed current run's pending reset without consuming a replacement run's request. */
    @Synchronized
    fun failRun(run: Long): Boolean {
        if (run != currentFilterRun) return false
        if (explicitResetOwnerRun == run) {
            explicitResetOwnerRun = null
        }
        if (structuralResetOwnerRun == run) {
            structuralResetOwnerRun = null
        }
        return true
    }

    /** Returns whether [token] is the latest published reset and has not been completed. */
    @Synchronized
    fun isViewportResetPending(token: Long): Boolean =
        token == viewportResetToken && token > consumedViewportResetToken &&
            pendingViewportResetOrigin != null

    /** Marks a published viewport reset token as completed. */
    @Synchronized
    fun consumeViewportReset(token: Long): Boolean {
        if (!isViewportResetPending(token)) return false
        consumedViewportResetToken = token
        pendingViewportResetOrigin = null
        return true
    }
}
