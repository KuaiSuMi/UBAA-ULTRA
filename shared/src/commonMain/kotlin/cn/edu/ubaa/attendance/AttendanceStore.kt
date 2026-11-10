package cn.edu.ubaa.attendance

import cn.edu.ubaa.model.dto.SigninClassDto
import com.russhwolf.settings.Settings
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AttendanceRecord(
    val courseId: String,
    val courseName: String,
    val date: String,
    val beginTime: String,
    val status: String,
    val message: String,
    val updatedAt: Long,
)

object AttendanceStore {
  private var backing: Settings? = null
  var settings: Settings
    get() = backing ?: Settings().also { backing = it }
    set(value) {
      backing = value
    }

  private val writeMutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private val revision = MutableStateFlow(0L)
  val changes = revision.asStateFlow()

  fun enabled(account: String): Boolean = settings.getBoolean("attendance_enabled_$account", false)

  fun setEnabled(account: String, enabled: Boolean) {
    settings.putBoolean("attendance_enabled_$account", enabled)
    revision.update { it + 1 }
  }

  fun records(account: String, date: String): List<AttendanceRecord> =
      settings
          .getStringOrNull("attendance_${account}_$date")
          ?.let { runCatching { json.decodeFromString<List<AttendanceRecord>>(it) }.getOrNull() }
          .orEmpty()

  suspend fun save(account: String, incoming: AttendanceRecord) =
      writeMutex.withLock {
        val stored = records(account, incoming.date)
        val record =
            if ('|' in incoming.courseId) {
              stored
                  .singleOrNull {
                    '|' !in it.courseId &&
                        it.courseName == incoming.courseName &&
                        it.beginTime == incoming.beginTime
                  }
                  ?.let { incoming.copy(courseId = it.courseId) } ?: incoming
            } else incoming
        val existing =
            stored.filterNot {
              '|' in it.courseId &&
                  '|' !in record.courseId &&
                  it.courseName == record.courseName &&
                  it.beginTime == record.beginTime
            }
        // 已确认的成功不被随后超时或旧查询覆盖。
        val previous =
            existing.firstOrNull {
              it.courseId == record.courseId && it.beginTime == record.beginTime
            }
        if (previous?.status == "success" && record.status != "success") return@withLock
        val updated =
            existing.filterNot {
              it.courseId == record.courseId && it.beginTime == record.beginTime
            } + record
        settings.putString("attendance_${account}_${record.date}", json.encodeToString(updated))
        revision.update { it + 1 }
      }

  suspend fun sync(account: String, classes: List<SigninClassDto>, date: String = beijingDate()) {
    classes.forEach {
      save(
          account,
          AttendanceRecord(
              it.courseId,
              it.courseName,
              date,
              signinTime(it.classBeginTime),
              if (it.signStatus == 1) "success" else "pending",
              if (it.signStatus == 1) "已签到（校方记录）" else "未签到",
              Clock.System.now().toEpochMilliseconds(),
          ),
      )
    }
  }
}

fun beijingDate(): String =
    Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date.toString()

fun signinTime(value: String): String =
    Regex("""(\d{1,2}):(\d{2})""").find(value)?.let {
      "${it.groupValues[1].padStart(2, '0')}:${it.groupValues[2]}"
    } ?: value.trim()
