package cn.edu.ubaa.attendance

import cn.edu.ubaa.model.dto.SigninClassDto
import cn.edu.ubaa.model.dto.Week
import cn.edu.ubaa.repository.SemesterSchedule
import kotlinx.datetime.*
import kotlinx.serialization.Serializable

@Serializable
data class AttendanceLesson(
    val key: String,
    val courseName: String,
    val place: String,
    val date: String,
    val beginTime: String,
    val start: Long,
    val end: Long,
) {
  val reminderAt: Long
    get() = start - 10 * 60_000

  fun signinAt(jitterSeconds: Int): Long {
    require(jitterSeconds in -60..60)
    return start - 5 * 60_000 + jitterSeconds * 1000L
  }

  fun canSigninAt(plannedAt: Long, now: Long): Boolean =
      plannedAt in signinAt(-60)..signinAt(60) && now >= plannedAt && now < start
}

fun attendanceLessons(semesters: List<SemesterSchedule>): List<AttendanceLesson> =
    semesters
        .flatMap { semester ->
          semester.weeks.flatMap { week ->
            semester.schedules[week.serialNumber]?.arrangedList.orEmpty().mapNotNull { course ->
              runCatching {
                    val date = requireNotNull(attendanceDate(week, course.dayOfWeek))
                    val begin = LocalTime.parse(requireNotNull(course.beginTime))
                    val end = LocalTime.parse(requireNotNull(course.endTime))
                    require(end > begin)
                    val zone = TimeZone.of("Asia/Shanghai")
                    AttendanceLesson(
                        "${semester.termCode}|$date|${course.courseCode}|$begin",
                        course.courseName,
                        course.placeName.orEmpty(),
                        date.toString(),
                        begin.toString().take(5),
                        LocalDateTime(date, begin).toInstant(zone).toEpochMilliseconds(),
                        LocalDateTime(date, end).toInstant(zone).toEpochMilliseconds(),
                    )
                  }
                  .getOrNull()
            }
          }
        }
        .distinctBy { it.key }
        .sortedBy { it.start }

fun attendanceDate(week: Week, dayOfWeek: Int?): LocalDate? =
    runCatching {
          val day = requireNotNull(dayOfWeek).also { require(it in 1..7) }
          val first = LocalDate.parse(week.startDate.take(10))
          first.plus((day - first.dayOfWeek.ordinal - 1 + 7) % 7, DateTimeUnit.DAY).also {
            require(it <= LocalDate.parse(week.endDate.take(10)))
          }
        }
        .getOrNull()

fun findAttendanceRecord(
    records: List<AttendanceRecord>,
    date: String?,
    courseName: String,
    beginTime: String?,
): AttendanceRecord? =
    records
        .filter {
          it.date == date &&
              it.courseName.filterNot(Char::isWhitespace) ==
                  courseName.filterNot(Char::isWhitespace) &&
              it.beginTime == beginTime?.let(::signinTime)
        }
        .maxWithOrNull(
            compareBy<AttendanceRecord> { it.status == "success" }.thenBy { it.updatedAt }
        )

/** 课表代码和签到任务 ID 并不相同；仅在名称和开课时间唯一匹配时提交。 */
fun matchSigninClass(lesson: AttendanceLesson, classes: List<SigninClassDto>): SigninClassDto? =
    classes
        .filter {
          it.courseName.filterNot(Char::isWhitespace) ==
              lesson.courseName.filterNot(Char::isWhitespace) &&
              (it.classBeginTime.length < 10 ||
                  !it.classBeginTime.take(10).contains('-') ||
                  it.classBeginTime.take(10) == lesson.date) &&
              signinTime(it.classBeginTime) == lesson.beginTime &&
              it.courseId.isNotBlank()
        }
        .singleOrNull()
