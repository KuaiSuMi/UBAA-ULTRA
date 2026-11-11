package cn.edu.ubaa.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class LiveCamera(val label: String) {
  TEACHER("教师"),
  STUDENT("学生"),
  SCREEN("屏幕"),
}

data class LiveClassroom(
    val campus: String,
    val room: String,
    val teacher: String,
    val student: String,
    val screen: String,
) {
  val id: String
    get() = "$campus/$room"

  val cameras: List<LiveCamera>
    get() = LiveCamera.entries.filter { stream(it).isNotBlank() }

  fun stream(camera: LiveCamera): String =
      when (camera) {
        LiveCamera.TEACHER -> teacher
        LiveCamera.STUDENT -> student
        LiveCamera.SCREEN -> screen
      }
}

class LiveClassroomCatalog(val rooms: List<LiveClassroom>) {
  val campuses: List<String> = rooms.map { it.campus }.distinct()

  fun search(query: String, campus: String? = null): List<LiveClassroom> {
    val key = normalizeRoom(query)
    val queryCampus = campusIn(query)
    return rooms.filter {
      (campus == null || it.campus == campus) &&
          (queryCampus == null || it.campus == queryCampus) &&
          (key.isEmpty() ||
              normalizeRoom(it.room).contains(key) ||
              it.campus.contains(query.trim()))
    }
  }

  fun matchCourse(place: String?): LiveClassroom? {
    if (place.isNullOrBlank()) return null
    val campus = campusIn(place)
    val key = normalizeRoom(place)
    if (key.isEmpty()) return null
    return rooms
        .filter { (campus == null || campus == it.campus) && normalizeRoom(it.room) == key }
        .singleOrNull()
  }

  private fun campusIn(value: String): String? =
      campuses.firstOrNull { value.contains(it) || (it.startsWith("杭州") && value.contains("杭州")) }

  companion object {
    fun parse(json: String): LiveClassroomCatalog {
      val rooms =
          Json.parseToJsonElement(json).jsonArray.map { element ->
            val fields = element.jsonObject
            fun field(name: String) = fields.getValue(name).jsonPrimitive.content
            LiveClassroom(
                field("campus"),
                field("room"),
                field("teacher"),
                field("student"),
                field("screen"),
            )
          }
      require(rooms.isNotEmpty() && rooms.map { it.id }.distinct().size == rooms.size)
      val streamPattern =
          Regex(
              "(?:https://lmt[0-9]+\\.buaa\\.edu\\.cn:443|http://10\\.62\\.1\\.11:80)/live/_definst_/[A-Za-z0-9.]+/playlist\\.m3u8"
          )
      require(
          rooms.all { room ->
            room.campus.isNotBlank() &&
                room.room.isNotBlank() &&
                room.cameras.all { streamPattern.matches(room.stream(it)) }
          }
      )
      return LiveClassroomCatalog(rooms)
    }
  }
}

internal fun normalizeRoom(value: String): String =
    value
        .uppercase()
        .map { if (it in '\uFF01'..'\uFF5E') (it.code - 0xFEE0).toChar() else it }
        .joinToString("")
        .replace(Regex("杭州国际创新研究院|学院路校区|沙河校区|杭州校区|学院路|沙河|杭州"), "")
        .replace("新主楼", "")
        .replace(Regex("主楼\\s*(?=主)"), "")
        .replace("主楼", "主")
        .replace(Regex("第三教学楼|三号教学楼|三教"), "三")
        .replace(Regex("第二教学楼|二号教学楼|二教"), "二")
        .filter { it.isLetterOrDigit() }
