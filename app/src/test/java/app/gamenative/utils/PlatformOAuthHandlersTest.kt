package app.gamenative.utils

import android.content.Context
import app.gamenative.data.AmazonCredentials
import app.gamenative.data.EpicCredentials
import app.gamenative.data.GOGCredentials
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class PlatformOAuthHandlersTest {
    private val context = mockk<Context>()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `successful platform login starts recovery without synchronizing catalogs`() = runBlocking {
        mockkObject(GOGService.Companion)
        mockkObject(EpicService.Companion)
        mockkObject(AmazonService.Companion)
        coEvery { GOGService.authenticateWithCode(context, "gog") } returns Result.success(mockk<GOGCredentials>())
        coEvery { EpicService.authenticateWithCode(context, "epic") } returns Result.success(mockk<EpicCredentials>())
        coEvery { AmazonService.authenticateWithCode(context, "amazon") } returns Result.success(mockk<AmazonCredentials>())
        every { GOGService.startForDownloadRecovery(context) } just Runs
        every { EpicService.startForDownloadRecovery(context) } just Runs
        every { AmazonService.startForDownloadRecovery(context) } just Runs

        PlatformOAuthHandlers.handleGogAuthentication(context, "gog", this, {}, {}, {}, {})
        PlatformOAuthHandlers.handleEpicAuthentication(context, "epic", this, {}, {}, {}, {})
        PlatformOAuthHandlers.handleAmazonAuthentication(context, "amazon", this, {}, {}, {}, {})

        verify(exactly = 1) { GOGService.startForDownloadRecovery(context) }
        verify(exactly = 1) { EpicService.startForDownloadRecovery(context) }
        verify(exactly = 1) { AmazonService.startForDownloadRecovery(context) }
        verify(exactly = 0) { GOGService.start(context) }
        verify(exactly = 0) { GOGService.triggerLibrarySync(context) }
        verify(exactly = 0) { EpicService.start(context) }
        verify(exactly = 0) { EpicService.triggerLibrarySync(context) }
        verify(exactly = 0) { AmazonService.start(context) }
        verify(exactly = 0) { AmazonService.triggerLibrarySync(context) }
    }
}
