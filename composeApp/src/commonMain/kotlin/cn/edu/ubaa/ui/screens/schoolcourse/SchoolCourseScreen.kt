package cn.edu.ubaa.ui.screens.schoolcourse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.api.feature.*

val LocalSchoolCourseLauncher = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun SchoolCourseScreen(
    state: SchoolCourseState,
    onSearch: (SchoolCourseQuery, Int) -> Unit,
    onBack: () -> Unit,
) {
  var keyword by remember { mutableStateOf(state.query.keyword) }
  var field by remember { mutableStateOf(state.query.field) }
  var term by remember { mutableStateOf(state.query.term) }
  var showTerms by remember { mutableStateOf(false) }
  val keyboard = LocalSoftwareKeyboardController.current
  fun search() {
    keyboard?.hide()
    onSearch(SchoolCourseQuery(keyword, field, term), 1)
  }
  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
          "全校课程",
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.padding(vertical = 12.dp),
      )
      TextButton(onClick = onBack) { Text("返回") }
    }
    Text("本研系统课程库", style = MaterialTheme.typography.labelMedium)
    CourseReplayButton("", "全校排课时间与回放")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SchoolCourseField.entries.forEach { option ->
        FilterChip(
            selected = field == option,
            onClick = { field = option },
            enabled = !state.loading,
            label = { Text(if (option == SchoolCourseField.TEACHER) "课程负责人" else option.label) },
        )
      }
    }
    OutlinedTextField(
        value = keyword,
        onValueChange = { keyword = it },
        singleLine = true,
        enabled = !state.loading,
        label = { Text(field.label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Box {
        TextButton(onClick = { showTerms = true }, enabled = !state.loading) {
          Text(term?.name ?: "全部学期")
        }
        DropdownMenu(expanded = showTerms, onDismissRequest = { showTerms = false }) {
          DropdownMenuItem(
              text = { Text("全部学期") },
              onClick = {
                term = null
                showTerms = false
              },
          )
          state.terms.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.name) },
                onClick = {
                  term = option
                  showTerms = false
                },
            )
          }
        }
      }
      TextButton(onClick = { search() }, enabled = !state.loading) { Text("查询") }
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { message ->
      Text(message, color = MaterialTheme.colorScheme.error)
      TextButton(onClick = { search() }) { Text("重试") }
    }
    state.result?.let { result ->
      Text("共 ${result.total} 条 · 第 ${result.page} 页", style = MaterialTheme.typography.labelMedium)
      if (result.courses.isEmpty()) Text("没有符合条件的课程", Modifier.padding(vertical = 24.dp))
      LazyColumn(
          Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          contentPadding = PaddingValues(vertical = 12.dp),
      ) {
        items(result.courses, key = { it.id }) { course ->
          Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(course.name, style = MaterialTheme.typography.titleMedium)
              Text(course.code, style = MaterialTheme.typography.labelMedium)
              if (course.englishName.isNotBlank())
                  Text(course.englishName, style = MaterialTheme.typography.bodySmall)
              Text(
                  listOf(course.term, course.department)
                      .filter { it.isNotBlank() }
                      .joinToString(" · ")
              )
              Text("${course.credits} 学分 · ${course.hours} 学时")
              CourseReplayButton(course.name, "上课时间与回放")
              if (course.teacher.isNotBlank()) Text("课程负责人：${course.teacher}")
              Text(
                  listOf(course.category, course.exam, course.language, course.level)
                      .filter { it.isNotBlank() }
                      .joinToString(" · "),
                  style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(
            onClick = { onSearch(state.query, result.page - 1) },
            enabled = result.page > 1,
        ) {
          Text("上一页")
        }
        TextButton(onClick = { onSearch(state.query, result.page + 1) }, enabled = result.hasNext) {
          Text("下一页")
        }
      }
    }
  }
}
