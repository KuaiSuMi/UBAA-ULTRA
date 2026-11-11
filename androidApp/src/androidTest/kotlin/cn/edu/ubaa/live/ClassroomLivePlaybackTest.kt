package cn.edu.ubaa.live

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ClassroomLivePlaybackTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun campusStreamPlaysSwitchesViewsAndRestartsAfterBackground() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("liveCampusSmoke") == "true")
    val intent =
        Intent(instrumentation.targetContext, ClassroomLiveActivity::class.java)
            .putExtra("place", "J1-101")
            .putExtra("course", "课程直播测试")
    ActivityScenario.launch<ClassroomLiveActivity>(intent).use { scenario ->
      lateinit var activity: ClassroomLiveActivity
      scenario.onActivity { activity = it }
      fun playerView() = findPlayerView(activity.window.decorView)
      fun renderedPlayer(previous: ExoPlayer? = null): ExoPlayer {
        var result: ExoPlayer? = null
        awaitUi("直播未输出视频帧") {
          result = playerView()?.player as? ExoPlayer
          result !== previous &&
              result?.isPlaying == true &&
              (result?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
        }
        return result!!
      }
      val teacher = renderedPlayer()
      screenshot("live-teacher.png")
      var teacherUrl: String? = null
      instrumentation.runOnMainSync {
        teacherUrl = teacher.currentMediaItem?.localConfiguration?.uri.toString()
      }
      click("学生")
      val student = renderedPlayer(teacher)
      instrumentation.runOnMainSync {
        assertNotSame(teacher, student)
        assertNotEquals(teacherUrl, student.currentMediaItem?.localConfiguration?.uri.toString())
      }
      click("屏幕")
      val screen = renderedPlayer(student)
      instrumentation.runOnMainSync { assertNotSame(student, screen) }
      click("全屏")
      awaitUi("未进入横屏") {
        activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
            activity.window.decorView.width > activity.window.decorView.height
      }
      instrumentation.uiAutomation.waitForIdle(1000, 5000)
      if (findText("Got it") != null) click("Got it")
      screenshot("live-fullscreen.png")
      click("退出全屏")
      awaitUi("未退出全屏") {
        activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
      scenario.moveToState(Lifecycle.State.CREATED)
      awaitUi("后台未释放播放器") { playerView()?.player == null }
      scenario.moveToState(Lifecycle.State.RESUMED)
      val resumed = renderedPlayer(screen)
      instrumentation.runOnMainSync { assertNotSame(screen, resumed) }
      click("换教室")
      screenshot("live-picker.png")
      click("学院路")
      screenshot("live-classrooms.png")
      assertNotNull(findText("168 间教室"))
    }
  }

  private fun findPlayerView(view: View): PlayerView? {
    if (view is PlayerView) return view
    if (view is ViewGroup)
        for (i in 0 until view.childCount) {
          findPlayerView(view.getChildAt(i))?.let {
            return it
          }
        }
    return null
  }

  private fun awaitUi(message: String, condition: () -> Boolean) {
    val done = CountDownLatch(1)
    val handler = Handler(Looper.getMainLooper())
    val check =
        object : Runnable {
          override fun run() {
            if (condition()) done.countDown() else handler.postDelayed(this, 100)
          }
        }
    handler.post(check)
    try {
      assertTrue(message, done.await(25, TimeUnit.SECONDS))
    } finally {
      handler.removeCallbacks(check)
    }
    instrumentation.waitForIdleSync()
  }

  private fun click(text: String) {
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(500, 5000)
    val node = checkNotNull(findText(text)) { "找不到 $text" }
    var clickable: AccessibilityNodeInfo? = node
    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
    assertTrue("无法点击 $text", clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(500, 5000)
  }

  private fun findText(text: String): AccessibilityNodeInfo? {
    fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
      if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
      for (i in 0 until node.childCount) {
        node
            .getChild(i)
            ?.let { find(it) }
            ?.let {
              return it
            }
      }
      return null
    }
    return find(instrumentation.uiAutomation.rootInActiveWindow)
  }

  private fun screenshot(name: String) {
    instrumentation.uiAutomation.waitForIdle(1000, 5000)
    File(instrumentation.targetContext.filesDir, name).outputStream().use {
      instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
