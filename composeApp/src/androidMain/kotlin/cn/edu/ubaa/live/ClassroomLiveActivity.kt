package cn.edu.ubaa.live

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cn.edu.ubaa.ui.theme.UBAATheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClassroomLiveActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      UBAATheme {
        Surface {
          ClassroomLiveScreen(
              place = intent.getStringExtra("place"),
              course = intent.getStringExtra("course"),
              activity = this,
          )
        }
      }
    }
  }
}

@Composable
private fun ClassroomLiveScreen(place: String?, course: String?, activity: ComponentActivity) {
  var catalog by remember { mutableStateOf<LiveClassroomCatalog?>(null) }
  var loadError by remember { mutableStateOf(false) }
  var loadAttempt by remember { mutableIntStateOf(0) }
  var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
  var initialized by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf(place.orEmpty()) }
  var campus by rememberSaveable { mutableStateOf<String?>(null) }
  var cameraName by rememberSaveable { mutableStateOf(LiveCamera.TEACHER.name) }
  var fullscreen by rememberSaveable { mutableStateOf(false) }
  val selected = catalog?.rooms?.firstOrNull { it.id == selectedId }
  val camera =
      LiveCamera.valueOf(cameraName).takeIf { selected == null || it in selected.cameras }
          ?: selected?.cameras?.firstOrNull()

  LaunchedEffect(loadAttempt) {
    loadError = false
    try {
      val loaded =
          withContext(Dispatchers.IO) {
            activity.assets.open("live/classrooms.json").bufferedReader().use {
              LiveClassroomCatalog.parse(it.readText())
            }
          }
      catalog = loaded
      if (!initialized) {
        selectedId = loaded.matchCourse(place)?.id
        initialized = true
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      loadError = true
    }
  }

  DisposableEffect(fullscreen) {
    val enteredFullscreen = fullscreen
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (enteredFullscreen) {
      activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      controller.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    onDispose {
      if (enteredFullscreen) {
        controller.show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
    }
  }
  fun back() {
    when {
      fullscreen -> fullscreen = false
      selected != null -> selectedId = null
      else -> activity.finish()
    }
  }
  BackHandler(enabled = fullscreen || selected != null) { back() }

  Column(Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.safeDrawingPadding())) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { back() }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
      }
      Column(Modifier.weight(1f)) {
        Text(selected?.room ?: "教室直播", style = MaterialTheme.typography.titleLarge)
        if (!fullscreen) {
          Text(selected?.campus ?: course ?: "选择教室观看直播", style = MaterialTheme.typography.bodySmall)
        }
      }
      if (selected != null) {
        TextButton(onClick = { fullscreen = !fullscreen }) {
          Text(if (fullscreen) "退出全屏" else "全屏")
        }
        if (!fullscreen)
            TextButton(
                onClick = {
                  selectedId = null
                  query = ""
                }
            ) {
              Text("换教室")
            }
      }
    }
    when {
      loadError ->
          Column(Modifier.padding(16.dp)) {
            Text("教室列表加载失败")
            TextButton(onClick = { loadAttempt++ }) { Text("重试") }
          }
      catalog == null ->
          Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
      selected != null -> {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          selected.cameras.forEach { item ->
            FilterChip(
                selected = camera == item,
                onClick = { cameraName = item.name },
                label = { Text(item.label) },
            )
          }
        }
        if (camera != null) {
          ClassroomPlayer(
              url = selected.stream(camera),
              modifier =
                  if (fullscreen) Modifier.fillMaxWidth().weight(1f)
                  else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
          )
        } else Text("该教室暂无直播地址", Modifier.padding(16.dp))
        if (!fullscreen) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!course.isNullOrBlank() && catalog?.matchCourse(place)?.id == selected.id)
                Text(course, style = MaterialTheme.typography.titleMedium)
            Text(
                "${selected.campus} · ${selected.room}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (camera != null)
                Text(
                    "${camera.label}视角 · 实时直播",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
          }
        }
      }
      else ->
          ClassroomPicker(catalog!!, query, { query = it }, campus, { campus = it }) {
            selectedId = it.id
            cameraName = LiveCamera.TEACHER.name
          }
    }
  }
}

@Composable
private fun ColumnScope.ClassroomPicker(
    catalog: LiveClassroomCatalog,
    query: String,
    onQuery: (String) -> Unit,
    campus: String?,
    onCampus: (String?) -> Unit,
    onSelect: (LiveClassroom) -> Unit,
) {
  OutlinedTextField(
      value = query,
      onValueChange = onQuery,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      placeholder = { Text("搜索教室，如 J1-101、主M101") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      singleLine = true,
  )
  LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      FilterChip(selected = campus == null, onClick = { onCampus(null) }, label = { Text("全部校区") })
    }
    items(catalog.campuses) { item ->
      FilterChip(selected = campus == item, onClick = { onCampus(item) }, label = { Text(item) })
    }
  }
  val rooms = remember(catalog, query, campus) { catalog.search(query, campus) }
  Text(
      "${rooms.size} 间教室",
      Modifier.padding(horizontal = 16.dp),
      style = MaterialTheme.typography.labelMedium,
  )
  if (rooms.isEmpty()) {
    Column(Modifier.padding(16.dp)) {
      Text("没有匹配的教室")
      TextButton(
          onClick = {
            onQuery("")
            onCampus(null)
          }
      ) {
        Text("查看全部教室")
      }
    }
  }
  LazyColumn(
      modifier = Modifier.weight(1f),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(rooms, key = { it.id }) { room ->
      OutlinedCard(onClick = { onSelect(room) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(room.room, style = MaterialTheme.typography.titleMedium)
            Text(room.campus, style = MaterialTheme.typography.bodySmall)
          }
          if (room.cameras.isEmpty()) Text("暂无直播地址", style = MaterialTheme.typography.labelSmall)
          else Icon(Icons.Default.PlayArrow, contentDescription = "观看 ${room.room}")
        }
      }
    }
  }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ClassroomPlayer(url: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var player by remember { mutableStateOf<ExoPlayer?>(null) }
  var failure by remember(url) { mutableStateOf<String?>(null) }
  var attempt by remember(url) { mutableIntStateOf(0) }
  var playing by remember(url) { mutableStateOf(false) }
  LifecycleStartEffect(url, attempt) {
    failure = null
    val current = ExoPlayer.Builder(context).build()
    current.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), true)
    current.setHandleAudioBecomingNoisy(true)
    current.addListener(
        object : Player.Listener {
          override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
          }

          override fun onPlayerError(error: PlaybackException) {
            val http =
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
            failure =
                when (http?.responseCode) {
                  404,
                  410 -> "当前视角暂无直播，可切换视角或重试"
                  401,
                  403 -> "直播服务未开放当前视频"
                  else -> "直播连接失败，请检查网络后重试"
                }
          }
        }
    )
    current.setMediaItem(MediaItem.fromUri(url))
    current.prepare()
    current.playWhenReady = true
    player = current
    onStopOrDispose {
      player = null
      playing = false
      current.release()
    }
  }
  Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
          PlayerView(it).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(false)
            setShowPreviousButton(false)
          }
        },
        update = {
          it.player = player
          it.keepScreenOn = playing
        },
        onRelease = {
          it.player = null
          it.keepScreenOn = false
        },
    )
    failure?.let { message ->
      Column(
          Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.85f)).padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(message, color = Color.White)
        TextButton(onClick = { attempt++ }) { Text("重试", color = Color.White) }
      }
    }
  }
}
