package cn.edu.ubaa.attendance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import cn.edu.ubaa.api.auth.*
import cn.edu.ubaa.api.feature.SigninApi
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.repository.ScheduleStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

object AttendanceScheduler {
  private const val CHANNEL = "course_attendance"
  const val WORK_TAG = "course_attendance"

  private fun preferences(context: Context) =
      context.getSharedPreferences(WORK_TAG, Context.MODE_PRIVATE)

  fun id(value: String): String =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
      }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val scheduleMutex = Mutex()

  fun initialize(context: Context) {
    val app = context.applicationContext
    scope.launch {
      merge(ScheduleStore.changes, AccountStore.changes).collect {
        try {
          reschedule(app).await()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {}
      }
    }
  }

  fun reschedule(context: Context) =
      scope.async {
        try {
          scheduleMutex.withLock { schedule(context.applicationContext) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          android.util.Log.e("Attendance", "后台任务调度失败", e)
          throw e
        }
      }

  private suspend fun schedule(context: Context) {
    val manager = WorkManager.getInstance(context)
    val prefs = preferences(context)
    val owner = AccountStore.active()?.user?.schoolid
    if (prefs.getString("owner", null) != owner) {
      manager.cancelAllWorkByTag(WORK_TAG).await()
      prefs.getString("owner", null)?.let { old ->
        context
            .getSharedPreferences("td_$old", Context.MODE_PRIVATE)
            .edit()
            .remove("job")
            .putString("status", "切换账号，任务已停止")
            .commit()
        manager.cancelUniqueWork("td_$old").await()
      }
      prefs.edit().clear().putString("owner", owner).commit()
    }
    if (owner == null || owner != ScheduleStore.account() || !AttendanceStore.enabled(owner)) {
      manager.cancelAllWorkByTag(WORK_TAG).await()
      prefs.edit().remove("scheduled").commit()
      return
    }
    val now = System.currentTimeMillis()
    val lessons = attendanceLessons(ScheduleStore.read(owner)).filter { it.start > now }
    val names = mutableSetOf<String>()
    for (lesson in lessons) {
      val key = id(owner + Json.encodeToString(lesson))
      if (!prefs.getBoolean("reminded_$key", false)) {
        val name = "class_reminder_$key"
        names += name
        val work =
            OneTimeWorkRequestBuilder<CourseReminderWorker>()
                .setInputData(
                    workDataOf(
                        "owner" to owner,
                        "lesson" to Json.encodeToString(lesson),
                        "key" to key,
                    )
                )
                .setInitialDelay(maxOf(0, lesson.reminderAt - now), TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG)
                .build()
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work).await()
      }
      if (!prefs.getBoolean("finished_$key", false)) {
        val name = "class_signin_$key"
        names += name
        val storedTime = prefs.getLong("signin_at_$key", 0)
        val signinAt =
            if (storedTime != 0L) storedTime
            else {
              lesson.signinAt(Random.nextInt(-60, 61)).also {
                prefs.edit().putLong("signin_at_$key", it).commit()
              }
            }
        val work =
            OneTimeWorkRequestBuilder<AttendanceWorker>()
                .setInputData(
                    workDataOf(
                        "owner" to owner,
                        "lesson" to Json.encodeToString(lesson),
                        "key" to key,
                        "signinAt" to signinAt,
                    )
                )
                .setInitialDelay(maxOf(0, signinAt - now), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work).await()
      }
    }
    val previous = prefs.getStringSet("scheduled", emptySet()).orEmpty()
    for (removed in previous - names) manager.cancelUniqueWork(removed).await()
    prefs.edit().putStringSet("scheduled", names).commit()
  }

  fun isCurrent(owner: String) =
      AccountStore.active()?.user?.schoolid == owner &&
          ScheduleStore.account() == owner &&
          AttendanceStore.enabled(owner)

  fun isFinished(context: Context, key: String) =
      preferences(context).getBoolean("finished_$key", false)

  @Synchronized
  fun markFinished(context: Context, key: String): Boolean {
    if (isFinished(context, key)) return false
    preferences(context).edit().putBoolean("finished_$key", true).commit()
    return true
  }

  fun remind(context: Context, owner: String, lesson: AttendanceLesson, key: String) {
    if (!isCurrent(owner) || System.currentTimeMillis() >= lesson.start) return
    val prefs = preferences(context)
    if (prefs.getBoolean("reminded_$key", false)) return
    notify(
        context,
        "reminder_$key",
        "${lesson.courseName} · 上课通知",
        "${lesson.beginTime} 开课 ${lesson.place}",
    )
    prefs.edit().putBoolean("reminded_$key", true).commit()
  }

  fun notify(context: Context, key: String, title: String, message: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "上课与打卡成功", NotificationManager.IMPORTANCE_DEFAULT)
        )
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val pending =
        PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    try {
      NotificationManagerCompat.from(context)
          .notify(
              key,
              1,
              NotificationCompat.Builder(context, CHANNEL)
                  .setSmallIcon(android.R.drawable.ic_dialog_info)
                  .setContentTitle(title)
                  .setContentText(message)
                  .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                  .setContentIntent(pending)
                  .setAutoCancel(true)
                  .build(),
          )
    } catch (_: SecurityException) {
      /* 用户关闭通知不应中断签到。 */
    }
  }
}

class AttendanceRestoreReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    val job = AttendanceScheduler.reschedule(context)
    job.invokeOnCompletion { pending.finish() }
  }
}

class CourseReminderWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val owner = inputData.getString("owner") ?: return Result.failure()
    val key = inputData.getString("key") ?: return Result.failure()
    val lesson =
        inputData.getString("lesson")?.let {
          runCatching { Json.decodeFromString<AttendanceLesson>(it) }.getOrNull()
        } ?: return Result.failure()
    AttendanceScheduler.remind(applicationContext, owner, lesson, key)
    return Result.success()
  }
}

class AttendanceWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val owner = inputData.getString("owner") ?: return Result.failure()
    val key = inputData.getString("key") ?: return Result.failure()
    if (AttendanceScheduler.isFinished(applicationContext, key)) return Result.success()
    val lesson =
        inputData.getString("lesson")?.let {
          runCatching { Json.decodeFromString<AttendanceLesson>(it) }.getOrNull()
        } ?: return Result.failure()
    val plannedAt = inputData.getLong("signinAt", 0)
    if (plannedAt !in lesson.signinAt(-60)..lesson.signinAt(60)) return Result.failure()
    if (!isCurrent(owner)) return Result.success()
    if (System.currentTimeMillis() < plannedAt) return Result.retry()
    if (System.currentTimeMillis() >= lesson.start) return finish(owner, lesson, false, "课前签到时间已结束")
    return try {
      withTimeout(60_000) {
        AuthSessionCoordinator.mutex.withLock {
          if (!isCurrent(owner)) return@withLock Result.success()
          val auth = AuthService()
          val status =
              if (auth.hasPersistedSession()) auth.getAuthStatus()
              else kotlin.Result.failure(ApiCallException("登录已失效", code = "unauthenticated"))
          status.onFailure {
            if (!it.isExpiredSession()) throw it
            val account = requireNotNull(AccountStore.active())
            val password =
                account.password ?: return@withLock finish(owner, lesson, false, "请打开应用登录并开启记住密码")
            val preload = auth.preloadLoginState().getOrThrow()
            if (preload.captchaRequired)
                return@withLock finish(owner, lesson, false, "需要验证码，请打开应用完成验证")
            if (preload.userData == null) {
              val logged =
                  auth.login(account.username, password, execution = preload.execution).getOrThrow()
              check(logged.user.schoolid == owner) { "登录账号不一致" }
            } else check(preload.userData?.schoolid == owner) { "登录账号不一致" }
          }
          status.getOrNull()?.let { check(it.user.schoolid == owner) { "登录账号不一致" } }
          val api = SigninApi()
          val classes = api.getClasses(lesson.date).getOrThrow()
          check(classes.code == 200) { classes.message }
          AttendanceStore.sync(owner, classes.data, lesson.date)
          val match = matchSigninClass(lesson, classes.data)
          if (match == null) return@withLock retryOrFinish(owner, lesson, "尚未找到唯一匹配的签到任务")
          val previous =
              AttendanceStore.records(owner, lesson.date).firstOrNull {
                it.courseId == match.courseId && it.beginTime == lesson.beginTime
              }
          if (match.signStatus == 1 || previous?.status == "success")
              return@withLock finish(owner, lesson, true, "已签到", match.courseId)
          if (!isCurrent(owner)) return@withLock Result.success()
          if (!lesson.canSigninAt(plannedAt, System.currentTimeMillis()))
              return@withLock finish(owner, lesson, false, "课前签到时间已结束", match.courseId)
          val response = api.performSignin(match.courseId).getOrThrow()
          if (response.success) finish(owner, lesson, true, response.message, match.courseId)
          else retryOrFinish(owner, lesson, response.message, match.courseId)
        }
      }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      retryOrFinish(owner, lesson, "网络请求超时")
    } catch (e: CancellationException) {
      throw e
    } catch (e: CaptchaRequiredClientException) {
      finish(owner, lesson, false, "需要验证码，请打开应用完成验证")
    } catch (e: Exception) {
      retryOrFinish(owner, lesson, e.message ?: "签到失败")
    }
  }

  private fun isCurrent(owner: String) = AttendanceScheduler.isCurrent(owner)

  private suspend fun retryOrFinish(
      owner: String,
      lesson: AttendanceLesson,
      message: String,
      courseId: String = lesson.key,
  ): Result {
    if (!isCurrent(owner)) return Result.success()
    if (System.currentTimeMillis() + 10_000 >= lesson.start)
        return finish(owner, lesson, false, message, courseId)
    AttendanceStore.save(
        owner,
        AttendanceRecord(
            courseId,
            lesson.courseName,
            lesson.date,
            lesson.beginTime,
            "pending",
            "$message；稍后重试",
            System.currentTimeMillis(),
        ),
    )
    return Result.retry()
  }

  private suspend fun finish(
      owner: String,
      lesson: AttendanceLesson,
      success: Boolean,
      message: String,
      courseId: String = lesson.key,
  ): Result {
    if (!isCurrent(owner)) return Result.success()
    if (
        !success &&
            AttendanceStore.records(owner, lesson.date).any {
              it.courseName == lesson.courseName &&
                  it.beginTime == lesson.beginTime &&
                  it.status == "success"
            }
    ) {
      inputData.getString("key")?.let { AttendanceScheduler.markFinished(applicationContext, it) }
      return Result.success()
    }
    AttendanceStore.save(
        owner,
        AttendanceRecord(
            courseId,
            lesson.courseName,
            lesson.date,
            lesson.beginTime,
            if (success) "success" else "failed",
            message,
            System.currentTimeMillis(),
        ),
    )
    val firstResult =
        inputData.getString("key")?.let {
          AttendanceScheduler.markFinished(applicationContext, it)
        } == true
    if (success && firstResult)
        AttendanceScheduler.notify(
            applicationContext,
            "result_${lesson.key}",
            "${lesson.courseName} · 签到成功",
            message,
        )
    return Result.success()
  }
}
