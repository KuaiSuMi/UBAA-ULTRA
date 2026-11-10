package cn.edu.ubaa.td

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

data class TdMachine(val id: Int, val serial: String, val name: String, val entrance: Boolean)

object TdProtocol {
  const val HOST = "10.212.28.38"
  const val PORT = 8888
  val machines =
      listOf(
          TdMachine(2, "20211025001", "本部入口 1", true),
          TdMachine(3, "20220301004", "本部入口 3", true),
          TdMachine(4, "20230417001", "本部入口 2", true),
          TdMachine(5, "20210421003", "本部出口 2", false),
          TdMachine(6, "20210420002", "本部出口 1", false),
          TdMachine(7, "20220301003", "本部出口 3", false),
          TdMachine(8, "20210420005", "沙河入口 1", true),
          TdMachine(9, "20210511002", "沙河入口 2", true),
          TdMachine(10, "20210511003", "沙河入口 3", true),
          TdMachine(11, "20220218001", "沙河出口 1", false),
          TdMachine(12, "20220225001", "沙河出口 2", false),
          TdMachine(13, "20220225004", "沙河出口 3", false),
      )

  fun allowed(timestamp: Long): Boolean {
    val time =
        Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.of("Asia/Shanghai")).time
    val minute = time.hour * 60 + time.minute
    return minute in 450 until 600 || minute in 690 until 840 || minute in 930 until 1200
  }

  fun cardNumber(studentId: String, supplied: String): String {
    require(studentId.isNotBlank() && studentId.all(Char::isDigit)) { "学号须为数字" }
    val card =
        supplied.trim().ifEmpty {
          requireNotNull(studentId.toLongOrNull()) { "学号格式无效" }.toString(16)
        }
    require(card.length <= 32 && card.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
      "校园卡号须为十六进制"
    }
    return card.uppercase()
  }

  fun checkPayload(student: String, card: String, machineId: Int, timestamp: Long): ByteArray {
    require(allowed(timestamp)) { "仅允许在北京时间 07:30–10:00、11:30–14:00、15:30–20:00 打卡" }
    val machine = machines.single { it.id == machineId }
    return buildJsonObject {
          put("cardno", cardNumber(student, card))
          put("userno", student.uppercase())
          put("timestamp", timestamp.toString())
          put("type", 1)
          put("eventno", "802")
          put("ln", machine.id.toString())
          put("sn", machine.serial)
          put("schoolno", "10006")
        }
        .toString()
        .encodeToByteArray()
  }

  fun photoPayload(machineId: Int, timestamp: Long, photo: ByteArray): ByteArray {
    require(photo.isNotEmpty() && photo.size <= 5 * 1024 * 1024) { "照片须小于 5 MB" }
    return "${machines.single { it.id == machineId }.serial}_$timestamp".encodeToByteArray() + photo
  }

  fun responseMessage(payload: String, checkin: Boolean): String {
    val json = Json.parseToJsonElement(payload).jsonObject
    check(json["status"]?.jsonPrimitive?.contentOrNull == "success") { "TD 服务拒绝请求" }
    if (!checkin) return "照片已上传"
    val message = json["srvresp"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    check("成功" in message && "不成功" !in message && "未成功" !in message && "失败" !in message) {
      message.ifBlank { "TD 未返回打卡成功确认" }
    }
    return message
  }
}
