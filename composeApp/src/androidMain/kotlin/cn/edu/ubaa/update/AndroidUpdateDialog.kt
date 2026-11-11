package cn.edu.ubaa.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.edu.ubaa.api.auth.AndroidRelease
import cn.edu.ubaa.api.core.getDefaultEngine
import cn.edu.ubaa.ui.common.components.ReleaseNotesText
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*

@Composable
fun AndroidUpdateDialog(release: AndroidRelease, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var progress by remember(release) { mutableFloatStateOf(0f) }
  var downloading by remember(release) { mutableStateOf(false) }
  var ready by remember(release) { mutableStateOf<File?>(null) }
  var error by remember(release) { mutableStateOf<String?>(null) }
  var job by remember { mutableStateOf<Job?>(null) }
  fun install() {
    val apk = ready ?: return
    try {
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
      context.startActivity(
          Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      )
    } catch (e: Exception) {
      error = e.message ?: "无法打开安装器"
    }
  }
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls())
            install()
        else error = "请允许 UBAA 安装更新"
      }
  fun requestInstall() {
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
      permission.launch(
          Intent(
              Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:${context.packageName}"),
          )
      )
    } else install()
  }
  AlertDialog(
      onDismissRequest = { if (!downloading) onDismiss() },
      title = { Text("新版本 ${release.version}") },
      text = {
        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          ReleaseNotesText(release.notes.ifBlank { "更新至 ${release.version}" })
          if (downloading) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("正在下载 ${(progress * 100).toInt()}%")
          }
          if (ready != null) Text("下载完成，点击安装更新")
          error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        TextButton(
            enabled = !downloading,
            onClick = {
              error = null
              if (ready != null) requestInstall()
              else {
                downloading = true
                job =
                    scope.launch {
                      try {
                        ready = downloadUpdate(context, release) { progress = it }
                        requestInstall()
                      } catch (e: CancellationException) {
                        throw e
                      } catch (e: Exception) {
                        error = e.message ?: "下载失败，请重试"
                      } finally {
                        downloading = false
                      }
                    }
              }
            },
        ) {
          Text(if (ready == null) "下载并安装" else "安装更新")
        }
      },
      dismissButton = {
        TextButton(
            onClick = {
              job?.cancel()
              onDismiss()
            }
        ) {
          Text(if (downloading) "取消下载" else "稍后")
        }
      },
  )
}

private suspend fun downloadUpdate(
    context: Context,
    release: AndroidRelease,
    progress: (Float) -> Unit,
): File =
    withContext(Dispatchers.IO) {
      val directory = File(context.cacheDir, "updates").apply { mkdirs() }
      val temporary = File(directory, "update.part")
      val target = File(directory, "update.apk")
      val digest = MessageDigest.getInstance("SHA-256")
      val client =
          HttpClient(getDefaultEngine()) {
            install(HttpTimeout) {
              requestTimeoutMillis = 600_000
              connectTimeoutMillis = 15_000
              socketTimeoutMillis = 30_000
            }
          }
      try {
        client.prepareGet(release.url).execute { response ->
          check(response.status.value == 200) { "下载安装包失败（${response.status.value}）" }
          var received = 0L
          temporary.outputStream().use { output ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            while (true) {
              ensureActive()
              val read = channel.readAvailable(buffer)
              if (read == -1) break
              if (read == 0) continue
              received += read
              check(received <= release.bytes) { "安装包大小与发布信息不符" }
              output.write(buffer, 0, read)
              digest.update(buffer, 0, read)
              withContext(Dispatchers.Main) { progress(received.toFloat() / release.bytes) }
            }
          }
          check(received == release.bytes) { "安装包下载不完整，请重试" }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) {
          "安装包校验失败，请重新下载"
        }
        validateUpdatePackage(context, temporary, release.version)
        if (target.exists()) check(target.delete()) { "无法替换旧安装包" }
        check(temporary.renameTo(target)) { "无法保存安装包" }
        target
      } finally {
        client.close()
        temporary.delete()
      }
    }

@Suppress("DEPRECATION")
private fun validateUpdatePackage(context: Context, apk: File, version: String) {
  val flags =
      if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
      else PackageManager.GET_SIGNATURES
  val manager = context.packageManager
  val incoming =
      requireNotNull(manager.getPackageArchiveInfo(apk.absolutePath, flags)) { "安装包无法读取" }
  val installed = manager.getPackageInfo(context.packageName, flags)
  fun PackageInfo.code(): Long =
      if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
  fun PackageInfo.signers(): Set<String> =
      (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
          .orEmpty()
          .map { it.toCharsString() }
          .toSet()
  check(
      incoming.packageName == context.packageName &&
          incoming.versionName == version &&
          incoming.code() > installed.code()
  ) {
    "安装包版本不匹配"
  }
  check(incoming.signers().isNotEmpty() && incoming.signers() == installed.signers()) {
    "安装包签名与当前应用不一致"
  }
}
