package cn.edu.ubaa

import cn.edu.ubaa.api.auth.AndroidRelease
import cn.edu.ubaa.api.auth.AppAnnouncement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupDialogStateTest {
  @Test
  fun authenticationInitializationDoesNotWaitForStartupPrompts() = runTest {
    val promptsCanFinish = CompletableDeferred<Unit>()
    var authenticationInitialized = false
    var promptsFinished = false

    launchStartupTasks(
        scope = this,
        initializeAuthentication = { authenticationInitialized = true },
        checkStartupPrompts = {
          promptsCanFinish.await()
          promptsFinished = true
        },
    )

    runCurrent()

    assertTrue(authenticationInitialized)
    assertFalse(promptsFinished)

    promptsCanFinish.complete(Unit)
  }

  @Test
  fun announcementDialogWaitsUntilUpdateDialogIsClosed() {
    val announcement =
        AppAnnouncement(
            id = "2026-05-07-main",
            title = "公告",
            content = "公告正文",
        )
    val update =
        AndroidRelease("1.9.1", "", "https://github.com/KuaiSuMi/UBAA-ULTRA", 1, "0".repeat(64))

    assertFalse(shouldShowAnnouncementDialog(update, announcement))
    assertTrue(shouldShowAnnouncementDialog(null, announcement))
  }

  @Test
  fun confirmingAnnouncementMarksReadAndOpensConfiguredLink() {
    val openedLinks = mutableListOf<String>()
    val readIds = mutableListOf<String>()

    confirmAnnouncement(
        announcement =
            AppAnnouncement(
                id = "2026-05-07-main",
                title = "公告",
                content = "公告正文",
                linkUrl = "https://example.com/notice",
            ),
        openUri = { openedLinks.add(it) },
        markRead = { readIds.add(it) },
    )

    assertEquals(listOf("2026-05-07-main"), readIds)
    assertEquals(listOf("https://example.com/notice"), openedLinks)
  }

  @Test
  fun confirmingAnnouncementWithoutLinkOnlyMarksRead() {
    val openedLinks = mutableListOf<String>()
    val readIds = mutableListOf<String>()

    confirmAnnouncement(
        announcement =
            AppAnnouncement(
                id = "2026-05-07-main",
                title = "公告",
                content = "公告正文",
            ),
        openUri = { openedLinks.add(it) },
        markRead = { readIds.add(it) },
    )

    assertEquals(listOf("2026-05-07-main"), readIds)
    assertTrue(openedLinks.isEmpty())
  }
}
