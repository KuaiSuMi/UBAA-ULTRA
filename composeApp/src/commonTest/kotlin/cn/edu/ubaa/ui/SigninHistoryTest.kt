package cn.edu.ubaa.ui

import cn.edu.ubaa.api.feature.SigninApi
import cn.edu.ubaa.api.feature.SigninApiBackend
import cn.edu.ubaa.attendance.AttendanceStore
import cn.edu.ubaa.attendance.beijingDate
import cn.edu.ubaa.model.dto.*
import cn.edu.ubaa.repository.ScheduleStore
import cn.edu.ubaa.ui.screens.signin.SigninViewModel
import com.russhwolf.settings.MapSettings
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SigninHistoryTest {
  @Test
  fun viewingPastWeekFetchesAndMatchesExistingSchoolRecords() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      AttendanceStore.settings = MapSettings()
      ScheduleStore.settings = MapSettings()
      ScheduleStore.useAccount("111")
      val queriedDates = mutableListOf<String>()
      val backend =
          object : SigninApiBackend {
            override suspend fun getClasses(date: String): Result<SigninStatusResponse> {
              queriedDates += date
              return Result.success(
                  SigninStatusResponse(
                      200,
                      "ok",
                      listOf(SigninClassDto("task-$date", "软件工程", "$date 9:00:00", "10:40", 1)),
                  )
              )
            }

            override suspend fun performSignin(courseId: String): Result<SigninActionResponse> =
                error("只查询历史")
          }
      val week = Week("2026-01-12", "2026-01-18", "term", false, 1, "第1周")
      val vm = SigninViewModel(SigninApi { backend })
      vm.loadWeekRecords(week)
      advanceUntilIdle()
      assertEquals((12..18).map { "2026-01-$it" }, queriedDates)
      for (date in queriedDates) {
        val record = AttendanceStore.records("111", date).single()
        assertEquals("success", record.status)
        assertEquals("09:00", record.beginTime)
        assertEquals(
            record,
            cn.edu.ubaa.attendance.findAttendanceRecord(listOf(record), date, "软件工程", "09:00"),
        )
        assertTrue(AttendanceStore.records("222", date).isEmpty())
      }
      vm.loadWeekRecords(week)
      advanceUntilIdle()
      assertEquals(7, queriedDates.size)
      vm.loadWeekRecords(week, forceRefresh = true)
      advanceUntilIdle()
      assertEquals(14, queriedDates.size)
      assertNull(vm.uiState.value.historyError)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun failedHistoryRequestCanBeRetriedAndDoesNotEraseSuccess() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      AttendanceStore.settings = MapSettings()
      ScheduleStore.settings = MapSettings()
      ScheduleStore.useAccount("111")
      var fail = true
      val queriedDates = mutableListOf<String>()
      val backend =
          object : SigninApiBackend {
            override suspend fun getClasses(date: String): Result<SigninStatusResponse> {
              queriedDates += date
              if (fail && date == "2026-01-12")
                  return Result.failure(IllegalStateException("网络不可用"))
              return Result.success(
                  SigninStatusResponse(
                      200,
                      "ok",
                      listOf(SigninClassDto("id", "软件工程", "09:00", "10:40", 1)),
                  )
              )
            }

            override suspend fun performSignin(courseId: String): Result<SigninActionResponse> =
                error("只查询历史")
          }
      val week = Week("2026-01-12", "2026-01-18", "term", false, 1, "第1周")
      val vm = SigninViewModel(SigninApi { backend })
      vm.loadWeekRecords(week)
      advanceUntilIdle()
      assertEquals("网络不可用", vm.uiState.value.historyError)
      assertEquals("success", AttendanceStore.records("111", "2026-01-13").single().status)
      fail = false
      queriedDates.clear()
      vm.loadWeekRecords(week)
      advanceUntilIdle()
      assertEquals(listOf("2026-01-12"), queriedDates)
      assertNull(vm.uiState.value.historyError)
      assertEquals("success", AttendanceStore.records("111", "2026-01-12").single().status)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun successfulSubmissionIsSavedEvenIfSubsequentRefreshFails() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      AttendanceStore.settings = MapSettings()
      ScheduleStore.settings = MapSettings()
      ScheduleStore.useAccount("111")
      var submitted = false
      val backend =
          object : SigninApiBackend {
            override suspend fun getClasses(date: String): Result<SigninStatusResponse> =
                if (submitted) Result.failure(IllegalStateException("网络不可用"))
                else
                    Result.success(
                        SigninStatusResponse(
                            200,
                            "ok",
                            listOf(SigninClassDto("id", "软件工程", "08:00", "09:40", 0)),
                        )
                    )

            override suspend fun performSignin(courseId: String): Result<SigninActionResponse> {
              submitted = true
              return Result.success(SigninActionResponse(200, true, "成功"))
            }
          }
      val vm = SigninViewModel(SigninApi { backend })
      vm.loadTodayClasses()
      advanceUntilIdle()
      vm.performSignin("id")
      advanceUntilIdle()
      assertEquals("success", AttendanceStore.records("111", beijingDate()).single().status)
      assertTrue(AttendanceStore.records("222", beijingDate()).isEmpty())
      assertNotNull(vm.uiState.value.error)
    } finally {
      Dispatchers.resetMain()
    }
  }
}
