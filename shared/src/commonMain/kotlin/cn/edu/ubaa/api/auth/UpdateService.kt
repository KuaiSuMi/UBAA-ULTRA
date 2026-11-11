package cn.edu.ubaa.api.auth

import cn.edu.ubaa.BuildKonfig
import cn.edu.ubaa.api.core.getDefaultEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AndroidRelease(
    val version: String,
    val notes: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val digest: String? = null,
)

internal fun compareAppVersions(left: String, right: String): Int {
  fun parts(value: String): List<Int> {
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(value)) { "无法识别版本号：$value" }
    return value.split('.').map(String::toInt)
  }
  return parts(left)
      .zip(parts(right))
      .firstOrNull { it.first != it.second }
      ?.let { it.first.compareTo(it.second) } ?: 0
}

/** Android 更新直接查询本项目发布页，与校园登录会话分离。 */
class UpdateService(
    private val client: HttpClient =
        HttpClient(getDefaultEngine()) {
          install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
          }
        }
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun checkUpdate(clientVersion: String = BuildKonfig.VERSION): Result<AndroidRelease?> =
      try {
        val response =
            client.get("https://api.github.com/repos/KuaiSuMi/UBAA-ULTRA/releases/latest") {
              header("Accept", "application/vnd.github+json")
              header("User-Agent", "UBAA-Android/$clientVersion")
            }
        if (response.status == HttpStatusCode.NotFound) Result.success(null)
        else {
          check(response.status == HttpStatusCode.OK) { "检查更新失败（${response.status.value}）" }
          val release = json.decodeFromString<GithubRelease>(response.bodyAsText())
          val version = release.tag.removePrefix("v")
          if (
              release.draft || release.prerelease || compareAppVersions(version, clientVersion) <= 0
          )
              Result.success(null)
          else {
            val asset =
                requireNotNull(
                    release.assets.singleOrNull { it.name == "UBAA-Android-v$version.apk" }
                ) {
                  "新版本的 Android 安装包尚未发布"
                }
            val url = Url(asset.url)
            require(
                url.protocol.name == "https" &&
                    url.host == "github.com" &&
                    url.encodedPath.startsWith("/KuaiSuMi/UBAA-ULTRA/releases/download/")
            ) {
              "安装包地址无效"
            }
            val digest = requireNotNull(asset.digest?.removePrefix("sha256:")) { "安装包缺少校验信息" }
            require(Regex("[a-fA-F0-9]{64}").matches(digest) && asset.size > 0) { "安装包校验信息无效" }
            Result.success(
                AndroidRelease(
                    version,
                    release.body.orEmpty(),
                    asset.url,
                    asset.size,
                    digest.lowercase(),
                )
            )
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }

  fun close() = client.close()
}
