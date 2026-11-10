package cn.edu.ubaa.ui.screens.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.attendance.AttendanceRecord
import cn.edu.ubaa.attendance.AttendanceStore
import cn.edu.ubaa.model.dto.*
import cn.edu.ubaa.repository.ScheduleStore
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SchedulePagerTest {
  @get:Rule val compose = createComposeRule()

  @Before
  fun prepareAccount() {
    ScheduleStore.settings = MapSettings()
    AttendanceStore.settings = MapSettings()
    ScheduleStore.useAccount("111")
  }

  @Test
  fun attendanceAppearsOnItsCourseAndUpdatesWithoutOpeningADialog() {
    val weeks =
        listOf(
            Week("2026-11-09", "2026-11-15", "term", true, 1, "第1周"),
            Week("2026-11-16", "2026-11-22", "term", false, 2, "第2周"),
        )
    val morning =
        CourseClass("CS", "软件工程", null, null, "08:00", "09:40", 1, 2, "主楼", null, null, null, 2)
    val later =
        morning.copy(beginTime = "10:00", endTime = "11:40", beginSection = 3, endSection = 4)
    val schedule = WeeklySchedule(listOf(morning, later), "term", "term")
    val record = AttendanceRecord("server-id", "软件工程", "2026-11-10", "08:00", "success", "", 1)
    runBlocking {
      AttendanceStore.save("111", record)
      AttendanceStore.save(
          "111",
          record.copy(courseId = "later", beginTime = "10:00", status = "pending"),
      )
      AttendanceStore.save("111", record.copy(date = "2026-11-17", status = "failed"))
    }
    var selected by mutableStateOf(weeks[0])
    compose.setContent {
      MaterialTheme {
        Box(Modifier.size(420.dp, 700.dp)) {
          ScheduleWeekPager(
              weeks,
              selected,
              mapOf(1 to schedule, 2 to schedule),
              { selected = it },
              {},
          )
        }
      }
    }
    compose.onNodeWithTag("course-2026-11-10-CS-08:00").assertTextContains("已签到")
    compose.onNodeWithTag("course-2026-11-10-CS-10:00").assertTextContains("待签到")
    compose.runOnIdle {
      runBlocking {
        AttendanceStore.save(
            "111",
            record.copy(courseId = "later", beginTime = "10:00", status = "success"),
        )
      }
    }
    compose.onNodeWithTag("course-2026-11-10-CS-10:00").assertTextContains("已签到")
    compose.runOnIdle { selected = weeks[1] }
    compose.waitForIdle()
    compose.onNodeWithTag("course-2026-11-17-CS-08:00").assertTextContains("签到失败")
  }

  @Test
  fun dragShowsTwoDifferentWeeksThenSettlesAndButtonsAnimate() {
    val weeks =
        listOf(
            Week("2026-09-07", "2026-09-13", "20261", true, 1, "第1周"),
            Week("2026-09-14", "2026-09-20", "20261", false, 2, "第2周"),
        )
    fun course(name: String, day: Int) =
        CourseClass(name, name, null, null, "08:00", "09:35", 1, 2, null, null, null, null, day)
    val schedules =
        mapOf(
            1 to WeeklySchedule(listOf(course("Week A", 7)), "20261", "term"),
            2 to WeeklySchedule(listOf(course("Week B", 1)), "20261", "term"),
        )
    var selected by mutableStateOf(weeks[0])
    compose.setContent {
      MaterialTheme {
        Box(Modifier.size(700.dp, 600.dp)) {
          ScheduleWeekPager(weeks, selected, schedules, { selected = it }, {})
        }
      }
    }
    compose.onNodeWithText("Week A").assertIsDisplayed()
    compose.onNodeWithTag("week-pager").performTouchInput {
      down(Offset(center.x * 1.6f, 100f))
      moveTo(Offset(center.x * 0.6f, 100f), delayMillis = 400)
    }
    compose.onNodeWithText("Week A").assertIsDisplayed()
    compose.onNodeWithText("Week B").assertIsDisplayed()
    compose.onNodeWithTag("week-pager").performTouchInput {
      moveTo(Offset(center.x * 0.2f, 100f), delayMillis = 100)
      up()
    }
    compose.waitForIdle()
    compose.runOnIdle {
      assertEquals(2, selected.serialNumber)
      selected = weeks[0]
    }
    compose.waitForIdle()
    compose.onNodeWithText("Week A").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, selected.serialNumber) }
  }
}
