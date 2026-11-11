package cn.edu.ubaa.health

import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.api.auth.AuthSessionCoordinator
import cn.edu.ubaa.api.local.LocalAuthServiceBackend
import cn.edu.ubaa.api.local.LocalCookieStore
import cn.edu.ubaa.api.local.LocalUpstreamClientProvider
import cn.edu.ubaa.api.storage.AccountStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class TdTermSummary(
    val year: String,
    val term: String,
    val total: String,
    val counts: Map<String, String>,
)

data class FitnessTable(val title: String, val rows: List<List<String>>)

data class FitnessYear(val id: String, val label: String, val url: String, val selected: Boolean)

data class FitnessReport(val years: List<FitnessYear>, val tables: List<FitnessTable>)

/** 使用现有 SSO Cookie 进入健康云，所有查询直接连接校园网地址。 */
class HealthCloudService(
    private val clientProvider: () -> HttpClient = {
      LocalUpstreamClientProvider.newClient(
          cookieStorage = LocalCookieStore.storage(ConnectionMode.DIRECT),
          followRedirects = false,
      )
    },
    private val currentOwner: () -> String? = { AccountStore.active()?.user?.schoolid },
) {
  suspend fun loadTd(owner: String): Result<List<TdTermSummary>> =
      query(owner) { client ->
        document(client, "$HEALTH_ORIGIN/index.php?schoolno=10006")
        parseTdSummary(
            document(client, "$HEALTH_ORIGIN/main.php?module=stu&title=stu_sun_score").html()
        )
      }

  suspend fun loadFitness(owner: String, year: String? = null): Result<FitnessReport> =
      query(owner) { client ->
        val index = document(client, "$HEALTH_ORIGIN/index.php?schoolno=10006")
        var link = fitnessLink(index)
        if (link == null) {
          for (frame in frameLinks(index)) {
            link = fitnessLink(document(client, frame))
            if (link != null) break
          }
        }
        var page = document(client, requireNotNull(link) { "健康云页面未提供体测成绩入口" })
        if (year != null) {
          val target = requireNotNull(fitnessYears(page).find { it.id == year }) { "健康云未提供该年度成绩" }
          if (!target.selected) page = document(client, target.url)
        }
        val tables =
            parseFitnessTables(page.html()).ifEmpty {
              frameLinks(page).flatMap { parseFitnessTables(document(client, it).html()) }
            }
        check(tables.isNotEmpty()) { "健康云暂未返回体测成绩" }
        FitnessReport(fitnessYears(page), tables)
      }

  private suspend fun <T> query(owner: String, block: suspend (HttpClient) -> T): Result<T> =
      try {
        withTimeout(30_000) {
          AuthSessionCoordinator.mutex.withLock {
            check(currentOwner() == owner) { "账号已切换，请重新打开健康云" }
            suspend fun request(): T {
              val client = clientProvider()
              return try {
                block(client)
              } finally {
                client.close()
              }
            }
            val result =
                try {
                  request()
                } catch (e: HealthSessionExpired) {
                  val account = AccountStore.active()?.takeIf { it.user.schoolid == owner }
                  val password = account?.password ?: throw e
                  LocalAuthServiceBackend().establishDirectSsoSession(account.username, password)
                  request()
                }
            check(currentOwner() == owner) { "账号已切换，请重新打开健康云" }
            Result.success(result)
          }
        }
      } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Result.failure(IllegalStateException("健康云连接超时，请连接校园网后重试", e))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }

  private suspend fun document(client: HttpClient, url: String): Document {
    var target = url
    repeat(10) {
      val uri = java.net.URI(target)
      // 健康云通过 HTTPS SSO 验证后返回校内 HTTP 回调，仅跟随这两个站点。
      require(
          campusLink(target) ||
              (uri.scheme == "https" &&
                  uri.host == "sso.buaa.edu.cn" &&
                  uri.port in listOf(-1, 443))
      ) {
        "健康云返回了未知跳转地址"
      }
      val response = client.get(target) { header("Referer", "$HEALTH_ORIGIN/main.php") }
      if (response.status.value in 300..399) {
        target =
            uri.resolve(requireNotNull(response.headers["Location"]) { "健康云跳转地址为空" }).toString()
      } else {
        check(response.status.value == 200) { "健康云请求失败（${response.status.value}）" }
        val document =
            Jsoup.parse(
                response.body<ByteArray>().inputStream(),
                null,
                response.call.request.url.toString(),
            )
        if (
            document.select("input[type=password]").isNotEmpty() ||
                response.call.request.url.host != "10.212.28.38"
        )
            throw HealthSessionExpired()
        return document
      }
    }
    error("健康云登录跳转次数过多")
  }
}

internal const val HEALTH_ORIGIN = "http://10.212.28.38"

private class HealthSessionExpired : IllegalStateException("健康云需要重新认证，请重新登录北航账号")

private fun campusLink(url: String): Boolean =
    runCatching {
          val parsed = java.net.URI(url)
          parsed.scheme == "http" && parsed.host == "10.212.28.38" && parsed.port in listOf(-1, 80)
        }
        .getOrDefault(false)

internal fun fitnessLink(document: Document): String? =
    document
        .select("a[href]")
        .firstOrNull {
          val name = it.text().filterNot(Char::isWhitespace)
          ((name.contains("体测") || name.contains("体质")) && name.contains("成绩") ||
              it.attr("href").contains("title=stu_ht_score")) && campusLink(it.absUrl("href"))
        }
        ?.absUrl("href")

internal fun fitnessYears(document: Document): List<FitnessYear> =
    document
        .select("a[href]")
        .mapNotNull { link ->
          val url = link.absUrl("href")
          if (!campusLink(url)) return@mapNotNull null
          val query = io.ktor.http.Url(url).parameters
          if (query["title"] != "stu_ht_score") return@mapNotNull null
          val year = query["year"] ?: return@mapNotNull null
          FitnessYear(year, link.text(), url, link.parent()?.hasClass("menu4l_active") == true)
        }
        .distinctBy { it.id }

private fun frameLinks(document: Document): List<String> =
    document
        .select("frame[src], iframe[src]")
        .map { it.absUrl("src") }
        .filter(::campusLink)
        .distinct()
        .take(4)

internal fun parseTdSummary(html: String): List<TdTermSummary> {
  val document = Jsoup.parse(html)
  for (table in document.select("table")) {
    val rows = tableRows(table)
    val headerIndex =
        rows.indexOfFirst { row ->
          row.any { "TD合计" in it.replace(" ", "") } &&
              row.any { it == "学年" } &&
              row.any { it == "学期" }
        }
    if (headerIndex < 0) continue
    val header = rows[headerIndex]
    val year = header.indexOf("学年")
    val term = header.indexOf("学期")
    val total = header.indexOfFirst { "TD合计" in it.replace(" ", "") }
    return rows
        .drop(headerIndex + 1)
        .mapNotNull { row ->
          if (
              row.size <= maxOf(year, term, total) ||
                  !Regex("\\d{4}\\s*[-–—~至]\\s*\\d{4}").matches(row[year])
          )
              return@mapNotNull null
          val counts =
              header.indices
                  .filter { it !in listOf(year, term) && it < row.size }
                  .associate { header[it] to countText(row[it]) }
          TdTermSummary(row[year], row[term], countText(row[total]), counts)
        }
        .sortedWith(compareByDescending<TdTermSummary> { it.year }.thenByDescending { it.term })
  }
  error("健康云未返回 TD 次数统计")
}

private fun countText(text: String): String =
    Regex("^\\d+(?:\\.\\d+)?").find(text)?.value
        ?: if (text.isBlank() || text.filterNot(Char::isWhitespace) in listOf("（查看明细）", "(查看明细)"))
            "0"
        else text

internal fun parseFitnessTables(html: String): List<FitnessTable> =
    Jsoup.parse(html)
        .select("table")
        .filter { it.select("table").size == 1 }
        .mapNotNull { table ->
          val rows = tableRows(table)
          val text = rows.flatten().joinToString(" ")
          if (
              rows.size < 2 ||
                  "TD合计" in text ||
                  !Regex("总分|肺活量|身高|体重|BMI|50米|跳远|体测成绩|测试项目|体质健康|学年.*成绩").containsMatchIn(text)
          )
              null
          else FitnessTable(table.selectFirst("caption")?.text().orEmpty(), rows)
        }

/** 展开合并单元格，保持体测表中的项目与分数对应。 */
private fun tableRows(table: org.jsoup.nodes.Element): List<List<String>> {
  val rows = table.select("tr").filter { it.closest("table") == table }.take(1000)
  val grid = mutableMapOf<Int, MutableMap<Int, String>>()
  rows.forEachIndexed { rowIndex, row ->
    var column = 0
    for (cell in row.children().filter { it.tagName() in listOf("td", "th") }) {
      while (grid[rowIndex]?.containsKey(column) == true) column++
      val rowspan = (cell.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1, 64)
      val colspan = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 64)
      val text = cell.text().replace('\u00a0', ' ').trim()
      for (r in rowIndex until minOf(rows.size, rowIndex + rowspan)) {
        for (c in column until column + colspan) grid.getOrPut(r) { mutableMapOf() }[c] = text
      }
      column += colspan
    }
  }
  return rows.indices.mapNotNull { index ->
    grid[index]
        ?.let { cells -> (0..(cells.keys.maxOrNull() ?: 0)).map { cells[it].orEmpty() } }
        ?.takeIf { it.any(String::isNotBlank) }
  }
}
