package cn.edu.ubaa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.attendance.*
import cn.edu.ubaa.model.dto.TodayClass
import cn.edu.ubaa.repository.ScheduleStore
import cn.edu.ubaa.ui.screens.menu.HomeScreen
import cn.edu.ubaa.ui.screens.menu.LocalAttendanceSettings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class HomeAttendanceTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun homeShowsSettingsAndAttendanceOnTodaysCourse() {
    ScheduleStore.settings = MapSettings()
    AttendanceStore.settings = MapSettings()
    ScheduleStore.useAccount("111")
    runBlocking {
      AttendanceStore.save(
          "111",
          AttendanceRecord("id", "软件工程", beijingDate(), "08:00", "success", "", 1),
      )
    }
    compose.setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalAttendanceSettings provides { Text("自动签到设置") }) {
          Box(Modifier.size(420.dp, 800.dp)) {
            HomeScreen(
                todayClasses = listOf(TodayClass("软件工程", "主楼", "08:00-09:40", null)),
                isLoading = false,
                isRefreshing = false,
                error = null,
                todoItems = emptyList(),
                todoLoading = false,
                todoLoadingSources = emptyList(),
                todoFailedSources = emptyList(),
                signingTodoId = null,
                scoreUpdateNotice = null,
                onRetrySchedule = {},
                onRefresh = {},
                onOpenScoresClick = {},
                onDismissScoreNotice = {},
                onTodoClick = {},
                onSigninTodoClick = {},
            )
          }
        }
      }
    }
    compose.onNodeWithText("自动签到设置").assertIsDisplayed()
    compose.onNodeWithText("软件工程").assertIsDisplayed()
    compose.onNodeWithText("已签到").assertIsDisplayed()
  }
}
