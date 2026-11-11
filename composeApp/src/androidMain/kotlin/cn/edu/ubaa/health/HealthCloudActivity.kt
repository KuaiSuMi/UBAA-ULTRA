package cn.edu.ubaa.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.ui.theme.UBAATheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class HealthCloudActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val owner =
        AccountStore.active()?.user?.schoolid
            ?: run {
              finish()
              return
            }
    setContent { UBAATheme { Surface { HealthCloudScreen(owner, ::finish) } } }
  }
}

@Composable
private fun HealthCloudScreen(owner: String, onBack: () -> Unit) {
  val service = remember { HealthCloudService() }
  val scope = rememberCoroutineScope()
  var scores by remember(owner) { mutableStateOf<List<FitnessTable>>(emptyList()) }
  var years by remember(owner) { mutableStateOf<List<FitnessYear>>(emptyList()) }
  var selectedYear by remember(owner) { mutableStateOf<String?>(null) }
  var loading by remember(owner) { mutableStateOf(false) }
  var error by remember(owner) { mutableStateOf<String?>(null) }
  fun refresh(year: String? = selectedYear) {
    if (loading) return
    loading = true
    scope.launch {
      try {
        service
            .loadFitness(owner, year)
            .onSuccess {
              scores = it.tables
              years = it.years
              selectedYear = it.years.firstOrNull { item -> item.selected }?.id ?: year
              error = null
            }
            .onFailure { error = it.message ?: "体测成绩查询失败" }
      } finally {
        loading = false
      }
    }
  }
  HealthRefreshEffect {
    if (AccountStore.active()?.user?.schoolid == owner) refresh() else onBack()
  }
  Column(
      Modifier.fillMaxSize()
          .statusBarsPadding()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("健康云", style = MaterialTheme.typography.headlineSmall)
      TextButton(onClick = onBack) { Text("返回") }
    }
    Text("校园网 · 北航统一认证", style = MaterialTheme.typography.bodySmall)
    TdCountCard(owner)
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("体测成绩", style = MaterialTheme.typography.titleMedium)
          TextButton(onClick = { refresh() }, enabled = !loading) { Text("刷新") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          years.forEach { year ->
            FilterChip(
                selected = selectedYear == year.id,
                onClick = { refresh(year.id) },
                enabled = !loading,
                label = { Text(year.label) },
            )
          }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        for (table in scores) {
          if (table.title.isNotBlank()) Text(table.title, fontWeight = FontWeight.Medium)
          Column(Modifier.horizontalScroll(rememberScrollState())) {
            table.rows.forEachIndexed { index, row ->
              Row {
                row.forEach { value ->
                  Text(
                      value,
                      modifier = Modifier.width(112.dp).padding(8.dp),
                      fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                  )
                }
              }
              HorizontalDivider()
            }
          }
        }
      }
    }
  }
}

@Composable
fun TdCountCard(owner: String, refreshKey: String? = null, onDetails: (() -> Unit)? = null) {
  val service = remember { HealthCloudService() }
  val scope = rememberCoroutineScope()
  var terms by remember(owner) { mutableStateOf<List<TdTermSummary>>(emptyList()) }
  var loading by remember(owner) { mutableStateOf(false) }
  var error by remember(owner) { mutableStateOf<String?>(null) }
  var updatedAt by remember(owner) { mutableStateOf<String?>(null) }
  fun refresh() {
    if (loading) return
    loading = true
    scope.launch {
      try {
        service
            .loadTd(owner)
            .onSuccess {
              terms = it
              error = null
              updatedAt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
            }
            .onFailure { error = it.message ?: "TD 次数查询失败" }
      } finally {
        loading = false
      }
    }
  }
  HealthRefreshEffect(::refresh)
  LaunchedEffect(refreshKey) { if (refreshKey != null) refresh() }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("健康云 TD 次数", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = ::refresh, enabled = !loading) { Text("刷新") }
      }
      if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      val visible = if (onDetails == null) terms else terms.take(1)
      for (term in visible) {
        Text("${term.year} 学年 · 第 ${term.term} 学期")
        Text("${term.total} 次", style = MaterialTheme.typography.headlineMedium)
        if (onDetails == null)
            term.counts
                .filterKeys { !it.contains("合计") }
                .forEach { (label, count) -> Text("$label：$count") }
      }
      if (!loading && error == null && updatedAt != null && terms.isEmpty()) Text("暂无 TD 统计")
      updatedAt?.let { Text("获取于 $it · 校方统计每 24 小时更新", style = MaterialTheme.typography.bodySmall) }
      onDetails?.let { TextButton(onClick = it) { Text("查看健康云与体测成绩") } }
    }
  }
}

@Composable
private fun HealthRefreshEffect(refresh: () -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val latest by rememberUpdatedState(refresh)
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) latest()
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
}
