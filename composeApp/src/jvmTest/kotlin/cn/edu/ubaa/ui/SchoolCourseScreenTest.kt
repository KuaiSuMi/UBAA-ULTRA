package cn.edu.ubaa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.api.feature.*
import cn.edu.ubaa.ui.screens.menu.AdvancedFeaturesScreen
import cn.edu.ubaa.ui.screens.schoolcourse.*
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class SchoolCourseScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun searchAndPaginationKeepSubmittedFilters() {
    var request: Pair<SchoolCourseQuery, Int>? = null
    val active = SchoolCourseQuery("原查询")
    compose.setContent {
      MaterialTheme {
        Box(Modifier.size(400.dp, 800.dp)) {
          SchoolCourseScreen(
              SchoolCourseState(query = active, result = SchoolCoursePage(emptyList(), 1, 45)),
              { query, page -> request = query to page },
              {},
          )
        }
      }
    }
    compose.onNodeWithText("课程负责人").performClick()
    compose.onNode(hasSetTextAction()).performTextReplacement("张")
    compose.onNodeWithText("查询", substring = false).performClick()
    assertEquals(SchoolCourseQuery("张", SchoolCourseField.TEACHER) to 1, request)
    compose.onNodeWithText("下一页").performClick()
    assertEquals(active to 2, request)
  }

  @Test
  fun advancedMenuOpensSchoolCourses() {
    var opened = false
    compose.setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalSchoolCourseLauncher provides { opened = true }) {
          Box(Modifier.size(400.dp, 900.dp)) { AdvancedFeaturesScreen({}, {}, {}, {}) }
        }
      }
    }
    compose.onNodeWithText("全校课程").performClick()
    assertEquals(true, opened)
  }
}
