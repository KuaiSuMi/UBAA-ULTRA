package cn.edu.ubaa.health

import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.api.local.LocalUpstreamClientProvider
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.api.storage.SavedAccount
import cn.edu.ubaa.model.dto.UserData
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class HealthCloudServiceTest {
  @BeforeTest
  fun setup() {
    AccountStore.settings = MapSettings()
  }

  private val td =
      """
      <table><tr><th>学年</th><th>学期</th><th>TD考勤机</th><th>活动转换</th><th>TD合计</th></tr>
      <tr><td>2025-2026</td><td>2</td><td>15（查看明细）</td><td>5</td><td>20</td></tr>
      <tr><td>2026-2027</td><td>1</td><td>12（查看明细）</td><td>( 查看明细 )</td><td>12</td></tr></table>
      """
          .trimIndent()
  private val fitness =
      """
      <table><caption>2026 年体测成绩</caption><tr><th rowspan="2">学年</th><th colspan="2">肺活量</th><th rowspan="2">总分</th></tr>
      <tr><th>成绩</th><th>得分</th></tr><tr><td>2026-2027</td><td>4200</td><td>85</td><td>87.5</td></tr></table>
      """
          .trimIndent()

  @Test
  fun extractsSchoolCountsAndSemesterInsteadOfLocalSubmissionCount() {
    val rows = parseTdSummary(td)
    assertEquals(listOf("2026-2027", "2025-2026"), rows.map { it.year })
    assertEquals("12", rows.first().total)
    assertEquals("0", rows.first().counts["活动转换"])
    assertEquals("15", rows.last().counts["TD考勤机"])
    assertEquals("20", rows.last().total)
  }

  @Test
  fun preservesMergedFitnessHeadersAndDecimalResults() {
    val result = parseFitnessTables(fitness).single()
    assertEquals("2026 年体测成绩", result.title)
    assertEquals(listOf("学年", "肺活量", "肺活量", "总分"), result.rows[0])
    assertEquals(listOf("学年", "成绩", "得分", "总分"), result.rows[1])
    assertEquals(listOf("2026-2027", "4200", "85", "87.5"), result.rows[2])
    assertTrue(parseFitnessTables(td).isEmpty())
  }

  @Test
  fun discoversActualFitnessLinkAndRestrictsItToCampusSite() {
    val page =
        Jsoup.parse(
            """<a href="https://example.com/grades">体测成绩</a><a href="main.php?module=stu&amp;title=actual_fitness">体质健康测试成绩</a>""",
            "$HEALTH_ORIGIN/index.php",
        )
    assertEquals("$HEALTH_ORIGIN/main.php?module=stu&title=actual_fitness", fitnessLink(page))
  }

  @Test
  fun visitsSchoolContextBeforeQueriesAndUsesDiscoveredFitnessUrl() = runBlocking {
    val paths = mutableListOf<String>()
    val clients = {
      HttpClient(
          MockEngine { request ->
            paths += request.url.encodedPath
            val body =
                when (request.url.encodedPath) {
                  "/index.php" -> {
                    assertEquals("10006", request.url.parameters["schoolno"])
                    """<a href="/fitness.php">体测成绩</a>"""
                  }
                  "/main.php" -> {
                    assertEquals("stu_sun_score", request.url.parameters["title"])
                    td
                  }
                  "/fitness.php" -> fitness
                  else -> error("Unexpected query")
                }
            respond(body)
          }
      ) {
        followRedirects = false
      }
    }
    val api = HealthCloudService(clients, { "111" })
    assertEquals("12", api.loadTd("111").getOrThrow().first().total)
    assertEquals("87.5", api.loadFitness("111").getOrThrow().tables.single().rows.last().last())
    assertEquals(listOf("/index.php", "/main.php", "/index.php", "/fitness.php"), paths)
  }

  @Test
  fun loginPageAndAccountSwitchNeverBecomeCounts() = runBlocking {
    assertTrue(
        HealthCloudService(
                { HttpClient(MockEngine { respond("<input type=password>") }) },
                { "111" },
            )
            .loadTd("111")
            .isFailure
    )
    var owner = "111"
    val api =
        HealthCloudService(
            {
              HttpClient(
                  MockEngine { request ->
                    if (request.url.encodedPath == "/main.php") {
                      owner = "222"
                      respond(td)
                    } else respond("<div>学生菜单</div>")
                  }
              )
            },
            { owner },
        )
    assertTrue(api.loadTd("111").isFailure)
  }

  @Test
  fun followsSsoHttpsBackToTheCampusHttpCallback() = runBlocking {
    val requests = mutableListOf<String>()
    val api =
        HealthCloudService(
            {
              HttpClient(
                  MockEngine { request ->
                    requests += request.url.host
                    when {
                      request.url.host == "sso.buaa.edu.cn" ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf("Location", "$HEALTH_ORIGIN/index.php?ticket=ST-test"),
                          )
                      request.url.encodedPath == "/index.php" &&
                          request.url.parameters["ticket"] == null ->
                          respond(
                              "",
                              HttpStatusCode.Found,
                              headersOf("Location", "https://sso.buaa.edu.cn/login?service=campus"),
                          )
                      request.url.encodedPath == "/main.php" -> respond(td)
                      else -> respond("<div>学生菜单</div>")
                    }
                  }
              ) {
                followRedirects = false
              }
            },
            { "111" },
        )
    assertEquals("12", api.loadTd("111").getOrThrow().first().total)
    assertEquals(
        listOf("10.212.28.38", "sso.buaa.edu.cn", "10.212.28.38", "10.212.28.38"),
        requests,
    )
  }

  @Test
  fun readsActualYearNavigationAndOnlyLeafScoreTables() {
    val html =
        """<table><tr><td><table><tr><td>姓名</td><td>测试用户</td></tr><tr><td>出生日期</td><td>2000-01-01</td></tr></table></td></tr></table>
      <table><tr><td class="white menu4l_active"><a href="?module=stu&amp;title=stu_ht_score&amp;year=2026-2027_1">2026年</a></td></tr></table>
      <table><tr><td><table><tr><td>测试年度</td><td>2026</td><td>成绩</td><td>80.0(缺项)</td></tr><tr><td>身高</td><td>1.75米</td><td>体重</td><td>65千克</td></tr></table></td></tr><tr><td>$fitness</td></tr></table>"""
    val year = fitnessYears(Jsoup.parse(html, "$HEALTH_ORIGIN/main.php")).single()
    assertEquals("2026-2027_1", year.id)
    assertTrue(year.selected)
    val tables = parseFitnessTables(html)
    assertEquals(2, tables.size)
    assertTrue(tables.first().rows.flatten().contains("80.0(缺项)"))
    assertFalse(tables.flatMap { it.rows.flatten() }.any { it.contains("出生日期") })
  }

  @Test
  fun unrecognizedResponseDoesNotDisplayZero() {
    assertFailsWith<IllegalStateException> { parseTdSummary("<html>服务器维护</html>") }
  }

  @Test
  fun renewsExpiredDirectSsoWithSavedAccountBeforeReadingCounts() = runBlocking {
    AccountStore.save(
        SavedAccount(UserData("测试", "111"), "111", "saved-health-password", ConnectionMode.WEBVPN)
    )
    var authenticated = false
    val previous = LocalUpstreamClientProvider.isolatedClientFactory
    LocalUpstreamClientProvider.isolatedClientFactory = { _, _ ->
      HttpClient(
          MockEngine { request ->
            when {
              request.url.host == "sso.buaa.edu.cn" && request.method == HttpMethod.Get ->
                  respond(
                      """<form><input name="execution" value="fresh-health-execution"><input name="username"><input name="password" type="password"></form>"""
                  )
              request.url.host == "sso.buaa.edu.cn" && request.method == HttpMethod.Post -> {
                val form = (request.body as FormDataContent).formData
                assertEquals("111", form["username"])
                assertEquals("saved-health-password", form["password"])
                assertEquals("fresh-health-execution", form["execution"])
                authenticated = true
                respond("ok")
              }
              request.url.host == "uc.buaa.edu.cn" -> respond("ok")
              else -> error("Unexpected SSO request")
            }
          }
      )
    }
    try {
      val api =
          HealthCloudService(
              {
                HttpClient(
                    MockEngine { request ->
                      when {
                        !authenticated -> respond("<input type=password>")
                        request.url.encodedPath == "/main.php" -> respond(td)
                        else -> respond("<div>学生菜单</div>")
                      }
                    }
                )
              },
              { "111" },
          )
      assertEquals("12", api.loadTd("111").getOrThrow().first().total)
      assertTrue(authenticated)
    } finally {
      LocalUpstreamClientProvider.isolatedClientFactory = previous
    }
  }

  @Test
  fun selectedFitnessYearLoadsItsPublishedUrl() = runBlocking {
    val visited = mutableListOf<String?>()
    val api =
        HealthCloudService(
            {
              HttpClient(
                  MockEngine { request ->
                    if (request.url.encodedPath == "/index.php")
                        respond(
                            """<a href="/main.php?module=stu&amp;title=stu_ht_score">体质测试</a>"""
                        )
                    else {
                      val year = request.url.parameters["year"]
                      visited += year
                      val navigation =
                          """<td class="${if (year == null) "menu4l_active" else ""}"><a href="?module=stu&amp;title=stu_ht_score&amp;year=2026-2027_1">2026年</a></td><td class="${if (year != null) "menu4l_active" else ""}"><a href="?module=stu&amp;title=stu_ht_score&amp;year=2025-2026_1">2025年</a></td>"""
                      respond(
                          "<table><tr>$navigation</tr></table>" +
                              fitness.replace(
                                  "2026-2027",
                                  if (year == null) "2026-2027" else "2025-2026",
                              )
                      )
                    }
                  }
              )
            },
            { "111" },
        )
    val report = api.loadFitness("111", "2025-2026_1").getOrThrow()
    assertEquals(listOf(null, "2025-2026_1"), visited)
    assertEquals("2025-2026_1", report.years.single { it.selected }.id)
    assertEquals("2025-2026", report.tables.single().rows.last().first())
  }
}
