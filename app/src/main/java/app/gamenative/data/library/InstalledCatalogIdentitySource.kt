package app.gamenative.data.library

import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.service.amazon.AmazonConstants
import app.gamenative.service.gog.GOGConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import timber.log.Timber

/** Store catalog identity that affects deterministic filesystem installation discovery. */
data class InstalledCatalogIdentity(
    /** Stable store identity used to update the matching database row. */
    val catalogId: String,
    /** Store-specific app or directory identity used to locate filesystem evidence. */
    val installIdentity: String,
)

/** Stable identity-only snapshot for stores whose catalog enables local installation discovery. */
data class InstalledCatalogIdentitySignature(
    /** GOG product and deterministic directory identities. */
    val gog: List<InstalledCatalogIdentity>,
    /** Epic catalog and app-name identities. */
    val epic: List<InstalledCatalogIdentity>,
    /** Amazon product and deterministic directory identities. */
    val amazon: List<InstalledCatalogIdentity>,
)

/** Observes only catalog fields whose changes can reveal previously unmatched installations. */
interface InstalledCatalogIdentitySource {
    /** Emits a stable signature without installation state or path fields that reconciliation updates. */
    fun observeIdentitySignatures(): Flow<InstalledCatalogIdentitySignature>
}

/** Room-backed identity source used to trigger in-process rescans after catalog upserts. */
class InstalledCatalogIdentitySourceImpl(
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
) : InstalledCatalogIdentitySource {
    override fun observeIdentitySignatures(): Flow<InstalledCatalogIdentitySignature> = combine(
        gogGameDao.getAll(),
        epicGameDao.getAll(),
        amazonGameDao.getAll(),
    ) { gogGames, epicGames, amazonGames ->
        InstalledCatalogIdentitySignature(
            gog = gogGames.mapNotNull { game ->
                if (game.id.isBlank() || game.title.isBlank()) {
                    Timber.e("Ignoring GOG catalog row without strict identity: id=%s", game.id)
                    null
                } else {
                    try {
                        InstalledCatalogIdentity(game.id, GOGConstants.gameDirectoryName(game.title))
                    } catch (exception: IllegalArgumentException) {
                        Timber.e(exception, "Ignoring GOG catalog row with invalid directory identity: id=%s", game.id)
                        null
                    }
                }
            }.sortedIdentities(),
            epic = epicGames.mapNotNull { game ->
                if (game.catalogId.isBlank() || game.appName.isBlank()) {
                    Timber.e("Ignoring Epic catalog row without strict identity: id=%d", game.id)
                    null
                } else {
                    InstalledCatalogIdentity(game.catalogId, game.appName)
                }
            }.sortedIdentities(),
            amazon = amazonGames.mapNotNull { game ->
                if (game.productId.isBlank() || game.title.isBlank()) {
                    Timber.e("Ignoring Amazon catalog row without strict identity: appId=%d", game.appId)
                    null
                } else {
                    InstalledCatalogIdentity(game.productId, AmazonConstants.gameDirectoryName(game.title))
                }
            }.sortedIdentities(),
        )
    }

    private fun List<InstalledCatalogIdentity>.sortedIdentities(): List<InstalledCatalogIdentity> =
        sortedWith(compareBy(InstalledCatalogIdentity::catalogId, InstalledCatalogIdentity::installIdentity))
}
