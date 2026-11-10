package cn.edu.ubaa.td

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.work.*
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.ui.theme.UBAATheme
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.launch

class TdActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val owner =
        AccountStore.active()?.user?.schoolid
            ?: run {
              finish()
              return
            }
    setContent { UBAATheme { Surface { TdScreen(owner, ::finish) } } }
  }
}

@Composable
private fun TdScreen(owner: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember(owner) { context.getSharedPreferences("td_$owner", Context.MODE_PRIVATE) }
  var entrance by remember { mutableIntStateOf(prefs.getInt("entrance", 2)) }
  var exit by remember { mutableIntStateOf(prefs.getInt("exit", 6)) }
  var card by remember { mutableStateOf(prefs.getString("card", "").orEmpty()) }
  var rounds by remember { mutableStateOf(prefs.getInt("rounds", 3).toString()) }
  var minWait by remember { mutableStateOf(prefs.getInt("min_wait", 180).toString()) }
  var maxWait by remember { mutableStateOf(prefs.getInt("max_wait", 240).toString()) }
  var status by remember { mutableStateOf(prefs.getString("status", "尚未开始").orEmpty()) }
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  DisposableEffect(prefs) {
    val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
          if (key == "status") status = prefs.getString("status", "").orEmpty()
        }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
  }
  fun start() {
    if (busy) return
    scope.launch {
      busy = true
      try {
        check(prefs.getString("job", null) == null) { "已有 TD 任务，请等待完成或先停止任务" }
        check(AccountStore.active()?.user?.schoolid == owner) { "账号已切换，请重新打开 TD" }
        val count =
            requireNotNull(rounds.toIntOrNull()) { "轮数须为整数" }
                .also { require(it in 1..10) { "轮数范围为 1–10" } }
        val min = requireNotNull(minWait.toLongOrNull()) { "等待时间须为整数" }
        val max = requireNotNull(maxWait.toLongOrNull()) { "等待时间须为整数" }
        require(min in 180..3600 && max in min..3600) { "每次间隔须为 180–3600 秒，且最小值不大于最大值" }
        require((entrance in 2..4 && exit in 5..7) || (entrance in 8..10 && exit in 11..13)) {
          "入口和出口须在同一校区"
        }
        val normalizedCard = TdProtocol.cardNumber(owner, card)
        require(TdProtocol.allowed(System.currentTimeMillis())) { "当前不在 TD 开放时间内" }
        val job = UUID.randomUUID().toString()
        prefs
            .edit()
            .putString("job", job)
            .putInt("entrance", entrance)
            .putInt("exit", exit)
            .putString("card", card)
            .putInt("rounds", count)
            .putInt("min_wait", min.toInt())
            .putInt("max_wait", max.toInt())
            .putString("status", "已安排 $count 轮打卡，后台执行中")
            .commit()
        val manager = WorkManager.getInstance(context)
        val requests =
            (0 until count * 2).map { step ->
              OneTimeWorkRequestBuilder<TdWorker>()
                  .setInputData(
                      workDataOf(
                          "owner" to owner,
                          "job" to job,
                          "step" to step,
                          "total" to count * 2,
                          "machine" to if (step % 2 == 0) entrance else exit,
                          "card" to normalizedCard,
                      )
                  )
                  .setInitialDelay(
                      if (step == 0) 0 else Random.nextLong(min, max + 1),
                      TimeUnit.SECONDS,
                  )
                  .addTag("td_$owner")
                  .build()
            }
        var chain =
            manager.beginUniqueWork("td_$owner", ExistingWorkPolicy.REPLACE, requests.first())
        requests.drop(1).forEach { chain = chain.then(it) }
        chain.enqueue()
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        status = e.message ?: "启动失败"
      } finally {
        busy = false
      }
    }
  }
  Column(
      Modifier.fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .verticalScroll(rememberScrollState())
          .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    TextButton(onClick = onBack) { Text("返回") }
    Text("TD 打卡", style = MaterialTheme.typography.headlineSmall)
    Text("账号：$owner")
    Text(
        "需要连接校园网。北京时间 07:30–10:00、11:30–14:00、15:30–20:00 开放。",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        card,
        { card = it },
        label = { Text("校园卡号（留空按学号转十六进制）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    MachinePicker("入口", entrance, true) { entrance = it }
    MachinePicker("出口", exit, false) { exit = it }
    OutlinedTextField(
        rounds,
        { rounds = it },
        label = { Text("打卡轮数") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
          minWait,
          { minWait = it },
          label = { Text("最小间隔（秒）") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f),
          singleLine = true,
      )
      OutlinedTextField(
          maxWait,
          { maxWait = it },
          label = { Text("最大间隔（秒）") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f),
          singleLine = true,
      )
    }
    Button(onClick = ::start, enabled = !busy) { Text(if (busy) "正在处理…" else "开始 TD 打卡") }
    TextButton(
        onClick = {
          prefs.edit().remove("job").putString("status", "任务已停止").commit()
          WorkManager.getInstance(context).cancelUniqueWork("td_$owner")
        }
    ) {
      Text("停止任务")
    }
    Text("执行状态", style = MaterialTheme.typography.titleMedium)
    Text(status)
  }
}

@Composable
private fun MachinePicker(
    label: String,
    selected: Int,
    entrance: Boolean,
    onSelect: (Int) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    OutlinedButton(onClick = { open = true }) {
      Text("$label：${TdProtocol.machines.firstOrNull { it.id == selected }?.name.orEmpty()}")
    }
    DropdownMenu(open, { open = false }) {
      TdProtocol.machines
          .filter { it.entrance == entrance }
          .forEach { machine ->
            DropdownMenuItem(
                text = { Text(machine.name) },
                onClick = {
                  onSelect(machine.id)
                  open = false
                },
            )
          }
    }
  }
}
