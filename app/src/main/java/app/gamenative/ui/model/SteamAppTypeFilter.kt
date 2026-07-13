package app.gamenative.ui.model

import app.gamenative.data.SteamApp
import app.gamenative.enums.AppType
import app.gamenative.ui.enums.AppFilter
import java.util.EnumSet

/** Filters Steam entries using the app-type choices shared by Library surfaces. */
internal fun filterSteamAppsByType(
    apps: List<SteamApp>,
    filters: EnumSet<AppFilter>,
): List<SteamApp> {
    val selectedTypes = AppFilter.getAppType(filters)
    return apps.filter { app ->
        app.type in selectedTypes || (!app.receivedPICS && app.type == AppType.invalid)
    }
}
