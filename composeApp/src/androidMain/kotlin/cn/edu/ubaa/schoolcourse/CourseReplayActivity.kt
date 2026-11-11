package cn.edu.ubaa.schoolcourse

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.live.ClassroomPlayer
import cn.edu.ubaa.ui.screens.schoolcourse.*
import cn.edu.ubaa.ui.theme.UBAATheme

class CourseReplayActivity : ComponentActivity() {
  private var owner: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val account =
        AccountStore.active()?.user?.schoolid
            ?: run {
              finish()
              return
            }
    owner = account
    enableEdgeToEdge()
    setContent {
      UBAATheme {
        Surface {
          val model = viewModel {
            CourseReplayViewModel(account, intent.getStringExtra("course").orEmpty())
          }
          val state by model.state.collectAsState()
          var fullscreen by rememberSaveable { mutableStateOf(false) }
          LaunchedEffect(model) { if (state.result == null && !state.loading) model.search() }
          DisposableEffect(fullscreen) {
            val entered = fullscreen
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (entered) {
              requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
              controller.systemBarsBehavior =
                  WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
              controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
              if (entered) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
              }
            }
          }
          fun back() {
            when {
              fullscreen -> fullscreen = false
              state.course != null -> model.back()
              else -> finish()
            }
          }
          BackHandler { back() }
          val lesson = state.lesson
          if (lesson == null) {
            CourseReplayScreen(
                state,
                model::search,
                model::openCourse,
                model::play,
                model::retry,
                ::back,
            )
          } else {
            var selected by rememberSaveable(lesson.id) { mutableIntStateOf(0) }
            Column(
                Modifier.fillMaxSize()
                    .then(if (fullscreen) Modifier else Modifier.safeDrawingPadding())
            ) {
              Row(
                  Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                TextButton(onClick = { back() }) { Text("返回") }
                TextButton(onClick = { fullscreen = !fullscreen }) {
                  Text(if (fullscreen) "退出全屏" else "全屏")
                }
              }
              if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
              state.error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::retry) { Text("重试") }
              }
              state.videos?.let { videos ->
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  videos.forEachIndexed { index, video ->
                    FilterChip(
                        selected == index,
                        onClick = { selected = index },
                        label = { Text(video.label) },
                    )
                  }
                }
                val video = videos.getOrNull(selected) ?: videos.first()
                ClassroomPlayer(
                    video.url,
                    if (fullscreen) Modifier.fillMaxWidth().weight(1f)
                    else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    replay = true,
                )
              }
              if (!fullscreen)
                  Column(
                      Modifier.padding(16.dp),
                      verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(state.course?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    Text(lesson.title)
                    Text(lesson.timeText)
                    Text("${lesson.teacher} · ${lesson.room}")
                  }
            }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (owner != AccountStore.active()?.user?.schoolid) finish()
  }
}
