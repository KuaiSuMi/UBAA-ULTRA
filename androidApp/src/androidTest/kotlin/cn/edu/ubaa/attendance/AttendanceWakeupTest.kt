package cn.edu.ubaa.attendance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.api.storage.SavedAccount
import cn.edu.ubaa.model.dto.UserData
import cn.edu.ubaa.repository.ScheduleStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttendanceWakeupTest {
  @Test
  fun exactWakeupRunsWorkerAndRecordsExpiredWindowWithoutActivity() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    fun shell(command: String) {
      ParcelFileDescriptor.AutoCloseInputStream(
              instrumentation.uiAutomation.executeShellCommand(command)
          )
          .use { it.readBytes() }
    }
    shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    shell("input keyevent KEYCODE_HOME")
    assertTrue(AttendanceScheduler.canScheduleWakeup(context))
    val originalAccounts = AccountStore.settings
    val originalSchedule = ScheduleStore.settings
    val originalAttendance = AttendanceStore.settings
    val owner = "alarm_test"
    val key = "expired-window-test"
    val lesson =
        AttendanceLesson(
            key,
            "定时签到测试",
            "测试教室",
            "2026-11-12",
            "08:00",
            System.currentTimeMillis() - 1_000,
            System.currentTimeMillis() + 60_000,
        )
    val intent =
        Intent(context, AttendanceWakeReceiver::class.java)
            .putExtra("owner", owner)
            .putExtra("key", key)
            .putExtra("lesson", Json.encodeToString(AttendanceLesson.serializer(), lesson))
            .putExtra("signinAt", lesson.signinAt(0))
    val pending =
        PendingIntent.getBroadcast(
            context,
            99,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val alarm = context.getSystemService(AlarmManager::class.java)
    try {
      AccountStore.settings = MapSettings()
      ScheduleStore.settings = MapSettings()
      AttendanceStore.settings = MapSettings()
      AccountStore.save(SavedAccount(UserData("定时测试", owner), owner, null, ConnectionMode.DIRECT))
      ScheduleStore.useAccount(owner)
      AttendanceStore.setEnabled(owner, true)
      AttendanceScheduler.reschedule(context).await()
      context
          .getSharedPreferences(AttendanceScheduler.WORK_TAG, 0)
          .edit()
          .remove("finished_$key")
          .remove("launched_$key")
          .commit()
      alarm.setExactAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          System.currentTimeMillis() + 3_000,
          pending,
      )
      withTimeout(45_000) {
        AttendanceStore.changes.first { AttendanceStore.records(owner, lesson.date).isNotEmpty() }
      }
      val record = AttendanceStore.records(owner, lesson.date).single()
      assertEquals("failed", record.status)
      assertEquals("课前签到时间已结束", record.message)
    } finally {
      alarm.cancel(pending)
      AttendanceStore.setEnabled(owner, false)
      AttendanceScheduler.reschedule(context).await()
      AccountStore.settings = originalAccounts
      ScheduleStore.settings = originalSchedule
      AttendanceStore.settings = originalAttendance
    }
  }
}
