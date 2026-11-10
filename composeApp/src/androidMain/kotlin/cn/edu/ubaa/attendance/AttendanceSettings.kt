package cn.edu.ubaa.attendance

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.repository.ScheduleRepository
import kotlinx.coroutines.launch

@Composable
fun AttendanceSettings() {
  val context = LocalContext.current
  val owner = AccountStore.active()?.user?.schoolid ?: return
  val revision by AttendanceStore.changes.collectAsState()
  val enabled = remember(owner, revision) { AttendanceStore.enabled(owner) }
  var notifications by remember {
    mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
  }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
      }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        AttendanceScheduler.reschedule(context)
      }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("自动签到", style = MaterialTheme.typography.titleMedium)
          Text("课前 10 分钟通知 · 课前 4–6 分钟自动签到", style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = enabled,
            enabled = !busy && (enabled || notifications),
            onCheckedChange = { value ->
              if (!value) {
                AttendanceStore.setEnabled(owner, false)
                AttendanceScheduler.reschedule(context)
              } else
                  scope.launch {
                    busy = true
                    error = null
                    try {
                      check(!AccountStore.active()?.password.isNullOrBlank()) { "请在账号登录时开启记住密码" }
                      ScheduleRepository().update().getOrThrow()
                      check(AccountStore.active()?.user?.schoolid == owner) { "账号已切换" }
                      AttendanceStore.setEnabled(owner, true)
                      AttendanceScheduler.reschedule(context).await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                      throw e
                    } catch (e: Exception) {
                      error = e.message ?: "开启失败"
                    } finally {
                      busy = false
                    }
                  }
            },
        )
      }
      if (busy) {
        Text("正在同步课表", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      if (!notifications)
          TextButton(
              onClick = {
                if (Build.VERSION.SDK_INT >= 33)
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
              }
          ) {
            Text("允许通知")
          }
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }
}
