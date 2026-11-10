package cn.edu.ubaa.attendance

import cn.edu.ubaa.model.dto.*
import cn.edu.ubaa.repository.SemesterSchedule
import com.russhwolf.settings.MapSettings
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class AttendancePlannerTest {
  private fun semester() =
      SemesterSchedule(
          emptyList(),
          "term",
          listOf(Week("2026-11-09", "2026-11-15", "term", true, 1, "第1周")),
          mapOf(
              1 to
                  WeeklySchedule(
                      listOf(
                          CourseClass(
                              "CS1",
                              "软件工程",
                              null,
                              null,
                              "08:00",
                              "09:40",
                              1,
                              2,
                              "主楼",
                              null,
                              null,
                              null,
                              2,
                          )
                      ),
                      "",
                      "",
                  )
          ),
      )

  @Test
  fun reminderUsesBeijingDateAndTenMinutesBeforeClass() {
    val lesson = attendanceLessons(listOf(semester())).single()
    assertEquals("2026-11-10", lesson.date)
    assertEquals(Instant.parse("2026-11-09T23:50:00Z").toEpochMilliseconds(), lesson.reminderAt)
    assertEquals(100L * 60_000, lesson.end - lesson.start)
  }

  @Test
  fun invalidTimesAreSkippedAndOverlappingSnapshotsAreDeduplicated() {
    val semester = semester()
    assertEquals(1, attendanceLessons(listOf(semester, semester)).size)
    val invalid =
        semester.copy(
            schedules =
                mapOf(
                    1 to
                        semester.schedules
                            .getValue(1)
                            .copy(
                                arrangedList =
                                    listOf(
                                        semester.schedules
                                            .getValue(1)
                                            .arrangedList
                                            .single()
                                            .copy(beginTime = null)
                                    )
                            )
                )
        )
    assertTrue(attendanceLessons(listOf(invalid)).isEmpty())
  }

  @Test
  fun onlyUniqueNameAndTimeMatchCanBeSubmitted() {
    val lesson = attendanceLessons(listOf(semester())).single()
    val correct = SigninClassDto("upstream-task", "软件工程", "2026-11-10 08:00:00", "09:40", 0)
    assertEquals(
        correct,
        matchSigninClass(
            lesson,
            listOf(correct, correct.copy(courseId = "later", classBeginTime = "10:00")),
        ),
    )
    assertNull(matchSigninClass(lesson, listOf(correct, correct.copy(courseId = "ambiguous"))))
    assertNull(matchSigninClass(lesson, listOf(correct.copy(courseName = "另一门课"))))
  }

  @Test
  fun matchingRejectsDifferentCalendarDate() {
    val lesson = attendanceLessons(listOf(semester())).single()
    assertNull(
        matchSigninClass(
            lesson,
            listOf(SigninClassDto("id", "软件工程", "2026-11-11 08:00:00", "09:40", 0)),
        )
    )
  }

  @Test
  fun signinWindowIsFourToSixMinutesBeforeClassAndNeverAtClassStart() {
    val lesson = attendanceLessons(listOf(semester())).single()
    assertEquals(lesson.start - 360_000, lesson.signinAt(-60))
    assertEquals(lesson.start - 300_000, lesson.signinAt(0))
    assertEquals(lesson.start - 240_000, lesson.signinAt(60))
    for (jitter in -60..60) {
      val planned = lesson.signinAt(jitter)
      assertFalse(lesson.canSigninAt(planned, planned - 1))
      assertTrue(lesson.canSigninAt(planned, planned))
      assertFalse(lesson.canSigninAt(planned, lesson.start))
      assertFalse(lesson.canSigninAt(planned, lesson.end))
    }
  }

  @Test
  fun displayedRecordMatchesCourseDateNameAndStartTime() {
    val record = AttendanceRecord("upstream-id", "软件 工程", "2026-11-10", "08:00", "success", "", 1)
    val records =
        listOf(
            record,
            record.copy(date = "2026-11-17", status = "failed"),
            record.copy(beginTime = "10:00", status = "pending"),
        )
    assertEquals("success", findAttendanceRecord(records, "2026-11-10", "软件工程", "08:00:00")?.status)
    assertEquals("failed", findAttendanceRecord(records, "2026-11-17", "软件工程", "08:00")?.status)
    assertEquals("pending", findAttendanceRecord(records, "2026-11-10", "软件工程", "10:00")?.status)
    assertNull(findAttendanceRecord(records, "2026-11-10", "另一门课", "08:00"))
    assertNull(findAttendanceRecord(records, "2026-11-11", "软件工程", "08:00"))
    assertEquals("2026-11-10", attendanceDate(semester().weeks.single(), 2)?.toString())
  }

  @Test
  fun expiredRetryUpdatesTheRealTaskInsteadOfAddingAnotherRow() = runTest {
    AttendanceStore.settings = MapSettings()
    val record = AttendanceRecord("task-id", "课程", "2026-11-10", "08:00", "pending", "等待", 1)
    AttendanceStore.save("one", record)
    AttendanceStore.save("one", record.copy(courseId = "term|date|course|08:00", status = "failed"))
    val result = AttendanceStore.records("one", record.date).single()
    assertEquals("task-id", result.courseId)
    assertEquals("failed", result.status)
    AttendanceStore.save("one", record.copy(status = "success"))
    AttendanceStore.save("one", record.copy(courseId = "term|date|course|08:00", status = "failed"))
    assertEquals("success", AttendanceStore.records("one", record.date).single().status)
  }

  @Test
  fun recordsAreAccountAndDateScopedAndSuccessIsNotLost() = runTest {
    AttendanceStore.settings = MapSettings()
    val record = AttendanceRecord("id", "课程", "2026-11-10", "08:00", "success", "已签到", 1)
    AttendanceStore.save("one", record)
    AttendanceStore.save("one", record.copy(status = "pending", updatedAt = 2))
    assertEquals("success", AttendanceStore.records("one", record.date).single().status)
    assertTrue(AttendanceStore.records("two", record.date).isEmpty())
    assertTrue(AttendanceStore.records("one", "2026-11-11").isEmpty())
    AttendanceStore.setEnabled("one", true)
    assertFalse(AttendanceStore.enabled("two"))
  }
}
