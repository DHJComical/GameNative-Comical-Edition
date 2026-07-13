package app.gamenative.events

import app.gamenative.data.SteamFriend
import app.gamenative.enums.LoginResult
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.ProfileInfoCallback

sealed interface SteamEvent<T> : Event<T> {
    enum class LogoutReason(val requiresReauthentication: Boolean) {
        USER_REQUEST(true),
        CREDENTIALS_REJECTED(true),
        CONNECTION_LOST(false),
        SERVICE_STOPPED(false),
    }

    data class Connected(val isAutoLoggingIn: Boolean) : SteamEvent<Unit>
    data class LoggedOut(
        val username: String?,
        val reason: LogoutReason,
    ) : SteamEvent<Unit>
    data class LogonEnded(val username: String?, val loginResult: LoginResult, val message: String? = null) :
        SteamEvent<Unit>
    data class LogonStarted(val username: String?) : SteamEvent<Unit>
    data class PersonaStateReceived(val persona: SteamFriend) : SteamEvent<Unit>
    data class QrAuthEnded(val success: Boolean, val message: String? = null) : SteamEvent<Unit>
    data class QrChallengeReceived(val challengeUrl: String) : SteamEvent<Unit>

    // data object AppInfoReceived : SteamEvent<Unit>
    data object ForceCloseApp : SteamEvent<Unit>
    data class PlayingBlocked(val remoteAppName: String?) : SteamEvent<Unit>
    data class Disconnected(val isTerminal: Boolean = false) : SteamEvent<Unit>
    data object RemotelyDisconnected : SteamEvent<Unit>
}
