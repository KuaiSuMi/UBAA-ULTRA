package cn.edu.ubaa.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.api.storage.AccountStore

val LocalManageAccounts = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun AccountManagerButton() {
  LocalManageAccounts.current?.let { open -> TextButton(onClick = open) { Text("账号管理 · 添加 / 切换") } }
}

@Composable
fun AccountManagerDialog(
    currentId: String?,
    onSelect: (String?) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
  val revision by AccountStore.changes.collectAsState()
  val accounts = remember(revision) { AccountStore.accounts() }
  var removing by remember { mutableStateOf<String?>(null) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("账号管理") },
      text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
          if (accounts.isEmpty()) Text("登录后可保存账号，便于下次切换")
          accounts.forEach { account ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
              TextButton(
                  onClick = { onSelect(account.user.schoolid) },
                  modifier = Modifier.weight(1f),
                  enabled = currentId != account.user.schoolid,
              ) {
                Column {
                  Text(account.user.name)
                  Text(
                      "${account.user.schoolid}${if (currentId == account.user.schoolid) " · 当前" else ""}"
                  )
                }
              }
              TextButton(onClick = { removing = account.user.schoolid }) { Text("移除") }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { onSelect(null) }) { Text("添加账号") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
  )
  removing?.let { id ->
    AlertDialog(
        onDismissRequest = { removing = null },
        title = { Text("移除账号") },
        text = { Text("移除 $id 的保存凭据。若移除当前账号，也会停止其后台签到。") },
        confirmButton = {
          TextButton(
              onClick = {
                onRemove(id)
                removing = null
              }
          ) {
            Text("移除")
          }
        },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
    )
  }
}
