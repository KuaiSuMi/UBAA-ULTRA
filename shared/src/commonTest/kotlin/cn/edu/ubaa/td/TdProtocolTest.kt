package cn.edu.ubaa.td

import kotlin.test.*
import kotlin.time.Instant
import kotlinx.serialization.json.*

class TdProtocolTest {
  @Test
  fun checksAllBeijingWindowBoundaries() {
    fun at(time: String) = Instant.parse("2026-11-10T${time}:00+08:00").toEpochMilliseconds()
    listOf("07:30", "09:59", "11:30", "13:59", "15:30", "19:59").forEach {
      assertTrue(TdProtocol.allowed(at(it)), it)
    }
    listOf("07:29", "10:00", "11:29", "14:00", "15:29", "20:00").forEach {
      assertFalse(TdProtocol.allowed(at(it)), it)
    }
  }

  @Test
  fun packetUsesDocumentedMachineAndMillisecondTimestamp() {
    val now = Instant.parse("2026-11-10T08:00:00+08:00").toEpochMilliseconds()
    val body =
        Json.parseToJsonElement(TdProtocol.checkPayload("12345678", "", 8, now).decodeToString())
            .jsonObject
    assertEquals("BC614E", body["cardno"]?.jsonPrimitive?.content)
    assertEquals("20210420005", body["sn"]?.jsonPrimitive?.content)
    assertEquals(now.toString(), body["timestamp"]?.jsonPrimitive?.content)
    assertContentEquals(
        "20210420005_${now}".encodeToByteArray() + byteArrayOf(1, 2),
        TdProtocol.photoPayload(8, now, byteArrayOf(1, 2)),
    )
  }

  @Test
  fun rejectsInvalidInputAndAmbiguousServerSuccess() {
    assertFailsWith<IllegalArgumentException> { TdProtocol.cardNumber("student", "") }
    assertFailsWith<IllegalArgumentException> { TdProtocol.cardNumber("123", "XYZ") }
    assertFailsWith<IllegalArgumentException> { TdProtocol.photoPayload(2, 1, byteArrayOf()) }
    assertEquals(
        "打卡成功",
        TdProtocol.responseMessage("""{"status":"success","srvresp":"打卡成功"}""", true),
    )
    listOf("打卡失败", "未成功", "处理完成", "").forEach {
      assertFailsWith<IllegalStateException> {
        TdProtocol.responseMessage("""{"status":"success","srvresp":"$it"}""", true)
      }
    }
  }
}
