package cn.edu.ubaa.ui.screens.schoolcourse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.api.feature.*

val LocalCourseReplayLauncher = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun CourseReplayButton(course: String, label: String = "课程回放") {
  val launch = LocalCourseReplayLauncher.current ?: return
  TextButton(onClick = { launch(course) }) { Text(label) }
}

@Composable
fun CourseReplayScreen(
    state: CourseReplayState,
    onSearch: (String, String, Int) -> Unit,
    onCourse: (ReplayCourse) -> Unit,
    onPlay: (CourseLesson) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
  var keyword by remember { mutableStateOf(state.keyword) }
  var term by remember { mutableStateOf(state.term) }
  var termsExpanded by remember { mutableStateOf(false) }
  val uri = LocalUriHandler.current
  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
          state.course?.name ?: "上课时间与回放",
          Modifier.weight(1f).padding(vertical = 12.dp),
          style = MaterialTheme.typography.headlineSmall,
      )
      TextButton(onClick = onBack) { Text("返回") }
    }
    if (state.course == null) {
      TextButton(onClick = { uri.openUri("$COURSE_REPLAY_SITE/courseCenter") }) {
        Text("智学北航 · 官方课程中心")
      }
      OutlinedTextField(
          keyword,
          { keyword = it },
          Modifier.fillMaxWidth(),
          label = { Text("课程名") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { onSearch(keyword, term, 1) }),
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Box {
          TextButton(onClick = { termsExpanded = true }) {
            Text(state.terms.firstOrNull { it.id == term }?.name ?: "全部学期")
          }
          DropdownMenu(termsExpanded, { termsExpanded = false }) {
            DropdownMenuItem(
                text = { Text("全部学期") },
                onClick = {
                  term = ""
                  termsExpanded = false
                },
            )
            state.terms.forEach { option ->
              DropdownMenuItem(
                  text = { Text(option.name) },
                  onClick = {
                    term = option.id
                    termsExpanded = false
                  },
              )
            }
          }
        }
        TextButton(onClick = { onSearch(keyword, term, 1) }, enabled = !state.loading) {
          Text("查询")
        }
      }
    } else {
      Text(
          listOf(state.course.term, state.course.teacher, state.course.department)
              .filter(String::isNotBlank)
              .joinToString(" · ")
      )
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let {
      Text(it, color = MaterialTheme.colorScheme.error)
      TextButton(onClick = onRetry) { Text("重试") }
    }
    if (state.course == null) {
      state.result?.let { result ->
        Text(
            "共 ${result.total} 个教学班 · 第 ${result.page} 页",
            style = MaterialTheme.typography.labelMedium,
        )
        if (result.courses.isEmpty()) Text("没有符合条件的课程", Modifier.padding(vertical = 16.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
          items(result.courses, key = { it.id }) { course ->
            OutlinedCard(onClick = { onCourse(course) }, modifier = Modifier.fillMaxWidth()) {
              Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                Text("${course.teacher} · ${course.department}")
                Text(course.term)
                Text("查看上课时间与回放", color = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(
              onClick = { onSearch(state.keyword, state.term, result.page - 1) },
              enabled = result.page > 1,
          ) {
            Text("上一页")
          }
          TextButton(
              onClick = { onSearch(state.keyword, state.term, result.page + 1) },
              enabled = result.hasNext,
          ) {
            Text("下一页")
          }
        }
      }
    } else {
      state.lessons?.let { lessons ->
        if (lessons.isEmpty()) Text("本课程暂无排课", Modifier.padding(vertical = 16.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
          items(lessons, key = { it.id }) { lesson ->
            Card(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(lesson.title, style = MaterialTheme.typography.titleMedium)
                Text(lesson.timeText, color = MaterialTheme.colorScheme.primary)
                Text(
                    listOf(lesson.teacher, lesson.room)
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                )
                if (lesson.hasReplay)
                    TextButton(onClick = { onPlay(lesson) }, enabled = !state.loading) {
                      Text("观看回放")
                    }
                else Text("暂无回放", style = MaterialTheme.typography.labelMedium)
              }
            }
          }
        }
      }
    }
  }
}
