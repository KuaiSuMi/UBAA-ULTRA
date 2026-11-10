package cn.edu.ubaa.td

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.attendance.AttendanceScheduler
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 协议为大端长度 + 单字节类型 + 数据；完整读取并限制响应大小。 */
internal fun tdRequest(type: Int, payload: ByteArray): String =
    Socket().use { socket ->
      socket.connect(InetSocketAddress(TdProtocol.HOST, TdProtocol.PORT), 10_000)
      socket.soTimeout = 15_000
      DataOutputStream(socket.getOutputStream()).apply {
        writeInt(payload.size)
        writeByte(type)
        write(payload)
        flush()
      }
      val input = DataInputStream(socket.getInputStream())
      val length = input.readInt()
      check(input.readUnsignedByte() == type) { "TD 响应类型不匹配" }
      check(length in 1..1_048_576) { "TD 响应长度无效" }
      ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }

class TdWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val owner = inputData.getString("owner") ?: return@withContext Result.failure()
        val job = inputData.getString("job") ?: return@withContext Result.failure()
        val step = inputData.getInt("step", 0)
        val machine = inputData.getInt("machine", 0)
        val card = inputData.getString("card").orEmpty()
        val prefs = applicationContext.getSharedPreferences("td_$owner", Context.MODE_PRIVATE)
        if (AccountStore.active()?.user?.schoolid != owner || prefs.getString("job", null) != job)
            return@withContext Result.failure()
        val marker = "$job:$step"
        if (prefs.getString("completed", null) == marker) return@withContext Result.success()
        // 服务端无幂等键：进程在提交后终止时，保留“待确认”并停止，避免重新打卡。
        if (prefs.getString("submitted", null) == marker) {
          prefs.edit().putString("status", "上次提交结果待确认").commit()
          return@withContext Result.failure()
        }
        try {
          val photo = applicationContext.assets.open("td/image2.jpg").use { it.readBytes() }
          val now = System.currentTimeMillis()
          val request = TdProtocol.checkPayload(owner, card, machine, now)
          val photoRequest = TdProtocol.photoPayload(machine, now, photo)
          currentCoroutineContext().ensureActive()
          check(
              AccountStore.active()?.user?.schoolid == owner && prefs.getString("job", null) == job
          ) {
            "账号或任务已切换"
          }
          prefs
              .edit()
              .putString("submitted", marker)
              .putString("status", "第 ${step / 2 + 1} 轮：正在${if (step % 2 == 0) "入口" else "出口"}打卡")
              .commit()
          val message = TdProtocol.responseMessage(tdRequest(80, request), checkin = true)
          TdProtocol.responseMessage(tdRequest(100, photoRequest), checkin = false)
          prefs
              .edit()
              .putString("completed", marker)
              .putString(
                  "status",
                  "第 ${step / 2 + 1} 轮${if (step % 2 == 0) "入口" else "出口"}成功：$message",
              )
              .commit()
          AttendanceScheduler.notify(
              applicationContext,
              "td_$job",
              "TD 打卡成功",
              prefs.getString("status", "").orEmpty(),
          )
          if (step + 1 == inputData.getInt("total", 0) && prefs.getString("job", null) == job) {
            prefs.edit().remove("job").commit()
          }
          Result.success()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          val message = e.message ?: "连接失败"
          if (prefs.getString("job", null) == job)
              prefs.edit().remove("job").putString("status", message).commit()
          Result.failure()
        }
      }
}
