package cn.edu.ubaa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.model.dto.CourseClass
import cn.edu.ubaa.ui.screens.classroom.LocalClassroomLiveLauncher
import cn.edu.ubaa.ui.screens.menu.AdvancedFeaturesScreen
import cn.edu.ubaa.ui.screens.schedule.CourseDetailScreen
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class ClassroomLiveEntryTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun courseDetailPassesTheCourseAndRoomToPlayer() {
    var opened: Pair<String?, String?>? = null
    val course =
        CourseClass(
            "001",
            "软件工程",
            null,
            null,
            "08:00",
            "09:35",
            1,
            2,
            "J1-101",
            null,
            null,
            null,
            1,
        )
    compose.setContent {
      MaterialTheme {
        CompositionLocalProvider(
            LocalClassroomLiveLauncher provides { place, title -> opened = place to title }
        ) {
          Box(Modifier.size(420.dp, 800.dp)) { CourseDetailScreen(course) }
        }
      }
    }
    compose.onNodeWithText("观看直播").performScrollTo().performClick()
    assertEquals("J1-101" to "软件工程", opened)
  }

  @Test
  fun advancedFeaturesOpensTheWholeClassroomCatalog() {
    var opened = false
    compose.setContent {
      MaterialTheme {
        CompositionLocalProvider(
            LocalClassroomLiveLauncher provides
                { place, title ->
                  assertEquals(null, place)
                  assertEquals(null, title)
                  opened = true
                }
        ) {
          Box(Modifier.size(420.dp, 1000.dp)) { AdvancedFeaturesScreen({}, {}, {}, {}) }
        }
      }
    }
    compose.onNodeWithText("教室直播").performClick()
    assertEquals(true, opened)
  }
}
