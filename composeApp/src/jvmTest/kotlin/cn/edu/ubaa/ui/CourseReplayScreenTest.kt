package cn.edu.ubaa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.api.feature.*
import cn.edu.ubaa.ui.screens.schoolcourse.*
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class CourseReplayScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun lessonShowsExactTimeAndOnlyOffersAvailableReplay() {
    val lesson = CourseLesson("1", "第10周星期2第3,4节", "教师", "F102", 1794275400, 1794281100, true)
    var played: CourseLesson? = null
    compose.setContent {
      MaterialTheme {
        Box(Modifier.size(420.dp, 900.dp)) {
          CourseReplayScreen(
              CourseReplayState(
                  course = ReplayCourse("1", "编译技术", "教师", "学院", "学期"),
                  lessons = listOf(lesson, lesson.copy(id = "2", title = "下一节", hasReplay = false)),
              ),
              { _, _, _ -> },
              {},
              { played = it },
              {},
              {},
          )
        }
      }
    }
    compose.onAllNodesWithText("2026-11-10 09:50–11:25").assertCountEquals(2)
    compose.onNodeWithText("暂无回放").assertExists()
    compose.onAllNodesWithText("观看回放").assertCountEquals(1)
    compose.onNodeWithText("观看回放").performClick()
    assertEquals(lesson, played)
  }
}
