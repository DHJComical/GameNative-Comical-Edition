package app.gamenative.ui.enums

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.R

/**
 * Destinations for Home Screen
 */
enum class HomeDestination(@StringRes val title: Int, val icon: ImageVector) {
    Library(R.string.destination_library, Icons.AutoMirrored.Filled.ViewList),
    Downloads(R.string.downloads_section_title, Icons.Filled.Download),
    Settings(R.string.settings_text, Icons.Filled.Settings),
    Storage(R.string.settings_storage_manage_title, Icons.Filled.Storage),
    GameLibraries(R.string.game_libraries_title, Icons.Filled.Storage),
}
