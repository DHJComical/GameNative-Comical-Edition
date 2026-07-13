package app.gamenative.data.library

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GameLibraryRootResolverTest {
    private lateinit var temporaryRoot: File
    private lateinit var resolver: GameLibraryRootResolver

    @Before
    fun setUp() {
        temporaryRoot = Files.createTempDirectory("game-library-root-resolver").toFile()
        resolver = GameLibraryRootResolverImpl()
    }

    @After
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun `Steam resolves root steamapps and common selections from marker evidence`() {
        val root = File(temporaryRoot, "Steam").apply { mkdirs() }
        marker(File(root, "steamapps/common/Installed Game"))

        assertResolves(GameSource.STEAM, root, root)
        assertResolves(GameSource.STEAM, File(root, "steamapps"), root)
        assertResolves(GameSource.STEAM, File(root, "steamapps/common"), root)
    }

    @Test
    fun `Steam native manifest requires its corresponding install directory`() {
        val root = File(temporaryRoot, "native-steam").apply { mkdirs() }
        val steamApps = File(root, "steamapps").apply { mkdirs() }
        writeSteamManifest(steamApps, "Native Game")

        assertResolves(GameSource.STEAM, steamApps, steamApps)

        File(steamApps, "common/Native Game").mkdirs()

        assertResolves(GameSource.STEAM, steamApps, root)
    }

    @Test
    fun `Steam rejects malformed mismatched and unfinished manifests as root evidence`() {
        val malformedRoot = File(temporaryRoot, "malformed-steam").apply { mkdirs() }
        File(malformedRoot, "steamapps/common/Native Game").mkdirs()
        File(malformedRoot, "steamapps/appmanifest_10.acf").writeText("not-vdf", Charsets.UTF_8)
        assertResolves(GameSource.STEAM, File(malformedRoot, "steamapps"), File(malformedRoot, "steamapps"))

        val mismatchedRoot = File(temporaryRoot, "mismatched-steam").apply { mkdirs() }
        File(mismatchedRoot, "steamapps/common/Native Game").mkdirs()
        writeSteamManifest(File(mismatchedRoot, "steamapps"), "Native Game", fileAppId = 10, appId = 11)
        assertResolves(GameSource.STEAM, File(mismatchedRoot, "steamapps"), File(mismatchedRoot, "steamapps"))

        val unfinishedRoot = File(temporaryRoot, "unfinished-steam").apply { mkdirs() }
        File(unfinishedRoot, "steamapps/common/Native Game").mkdirs()
        writeSteamManifest(File(unfinishedRoot, "steamapps"), "Native Game", stateFlags = 2)
        assertResolves(GameSource.STEAM, File(unfinishedRoot, "steamapps"), File(unfinishedRoot, "steamapps"))
    }

    @Test
    fun `GOG resolves root games and common selections from marker or info evidence`() {
        val markerRoot = File(temporaryRoot, "GOG-marker").apply { mkdirs() }
        marker(File(markerRoot, "games/common/Marker Game"))
        assertResolves(GameSource.GOG, markerRoot, markerRoot)
        assertResolves(GameSource.GOG, File(markerRoot, "games"), markerRoot)
        assertResolves(GameSource.GOG, File(markerRoot, "games/common"), markerRoot)

        val infoRoot = File(temporaryRoot, "GOG-info").apply { mkdirs() }
        File(infoRoot, "games/common/Native Game").apply {
            mkdirs()
            resolve("goggame-123.info").writeText("{\"gameId\":\"123\"}", Charsets.UTF_8)
        }
        assertResolves(GameSource.GOG, File(infoRoot, "games/common"), infoRoot)
    }

    @Test
    fun `GOG resolves common selection when only valid info is V2 nested`() {
        val root = File(temporaryRoot, "GOG-v2-nested").apply { mkdirs() }
        File(root, "games/common/Native Game/game_123/nested/goggame-123.info").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{\"gameId\":\"123\"}", Charsets.UTF_8)
        }

        assertResolves(GameSource.GOG, File(root, "games/common"), root)
    }

    @Test
    fun `Epic resolves root and games selections from marker or egstore manifest evidence`() {
        val markerRoot = File(temporaryRoot, "Epic-marker").apply { mkdirs() }
        marker(File(markerRoot, "games/Marker Game"))
        assertResolves(GameSource.EPIC, markerRoot, markerRoot)
        assertResolves(GameSource.EPIC, File(markerRoot, "games"), markerRoot)

        val manifestRoot = File(temporaryRoot, "Epic-manifest").apply { mkdirs() }
        writeEpicManifest(File(manifestRoot, "games/Native Game/.egstore/install.manifest"), "NativeApp")
        assertResolves(GameSource.EPIC, File(manifestRoot, "games"), manifestRoot)
    }

    @Test
    fun `invalid GOG and Epic metadata shapes do not rewrite selected layouts`() {
        val gogRoot = File(temporaryRoot, "invalid-gog").apply { mkdirs() }
        File(gogRoot, "games/common/Game/goggame-10.info").apply {
            parentFile?.mkdirs()
            writeText("not-json", Charsets.UTF_8)
        }
        assertResolves(GameSource.GOG, File(gogRoot, "games/common"), File(gogRoot, "games/common"))

        val epicRoot = File(temporaryRoot, "invalid-epic").apply { mkdirs() }
        File(epicRoot, "games/Game/.egstore/install.manifest").apply {
            parentFile?.mkdirs()
            writeText("not-manifest", Charsets.UTF_8)
        }
        assertResolves(GameSource.EPIC, File(epicRoot, "games"), File(epicRoot, "games"))
    }

    @Test
    fun `Amazon keeps a direct game root and descends from an Amazon parent into games`() {
        val directRoot = File(temporaryRoot, "Amazon-direct").apply { mkdirs() }
        marker(File(directRoot, "Direct Game"))
        assertResolves(GameSource.AMAZON, directRoot, directRoot)

        val parent = File(temporaryRoot, "Amazon").apply { mkdirs() }
        val games = File(parent, "games")
        marker(File(games, "Nested Game"))
        assertResolves(GameSource.AMAZON, parent, games)
        assertResolves(GameSource.AMAZON, games, games)
    }

    @Test
    fun `empty same-name layouts and single game directories are never rewritten`() {
        val emptySteamApps = File(temporaryRoot, "empty/steamapps/common").apply { mkdirs() }
        assertResolves(GameSource.STEAM, emptySteamApps, emptySteamApps)

        val emptyGogGames = File(temporaryRoot, "empty-gog/games/common").apply { mkdirs() }
        assertResolves(GameSource.GOG, emptyGogGames, emptyGogGames)

        val emptyEpicGames = File(temporaryRoot, "empty-epic/games").apply { mkdirs() }
        assertResolves(GameSource.EPIC, emptyEpicGames, emptyEpicGames)

        val emptyAmazonGames = File(temporaryRoot, "empty-amazon/games").apply { mkdirs() }
        val emptyAmazonParent = requireNotNull(emptyAmazonGames.parentFile)
        assertResolves(GameSource.AMAZON, emptyAmazonParent, emptyAmazonParent)

        val singleGame = File(temporaryRoot, "single-game").apply { mkdirs() }
        File(singleGame, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
        assertResolves(GameSource.AMAZON, singleGame, singleGame)
    }

    private fun assertResolves(source: GameSource, selected: File, expected: File) {
        assertEquals(expected.canonicalPath, resolver.resolve(source, selected.path))
    }

    private fun marker(directory: File) {
        directory.mkdirs()
        File(directory, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
    }

    private fun writeSteamManifest(
        steamApps: File,
        installDir: String,
        fileAppId: Int = 10,
        appId: Int = fileAppId,
        stateFlags: Int = 4,
    ) {
        steamApps.mkdirs()
        File(steamApps, "appmanifest_$fileAppId.acf").writeText(
            """
                "AppState"
                {
                    "appid" "$appId"
                    "StateFlags" "$stateFlags"
                    "installdir" "$installDir"
                }
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }

    private fun writeEpicManifest(file: File, appName: String) {
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(
            """
                {
                  "ManifestFileVersion":"013000000000",
                  "AppID":"001000000000",
                  "AppNameString":"$appName",
                  "BuildVersionString":"1"
                }
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }
}
