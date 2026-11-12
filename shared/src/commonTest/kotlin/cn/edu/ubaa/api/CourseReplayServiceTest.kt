package cn.edu.ubaa.api

import cn.edu.ubaa.api.feature.*
import cn.edu.ubaa.api.local.LocalCookieStore
import cn.edu.ubaa.api.local.LocalUpstreamClientProvider
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.api.storage.SavedAccount
import cn.edu.ubaa.model.dto.UserData
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class CourseReplayServiceTest {
  private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

  @Test
  fun upgradesOnlyKnownCasHostsAndPreservesEncodedTicket() {
    assertEquals(
        "https://yjapi.msa.buaa.edu.cn/casapi/?ticket=a%2Bb%3D",
        secureReplayRedirect("http://yjapi.msa.buaa.edu.cn:80/casapi/?ticket=a%2Bb%3D"),
    )
    assertFails { secureReplayRedirect("https://unrelated.example/callback") }
  }

  @Test
  fun parsesCalendarTimesAndOnlyVisibleLessons() {
    val lessons =
        CourseReplayParser.lessons(
            json(
                """{"code":0,"data":{"sub_list":{"2026":{"11":{"10":[
      {"id":"2","sub_title":"第10周星期2第3,4节","lecturer_name":"教师","room_name":"学院路校区F-102","class_begin":"1794275400","class_over":1794281100,"playback_status":"1","show":"yes","sub_delete":"0"},
      {"id":"3","show":"no","sub_delete":"0"}, {"id":"4","show":"yes","sub_delete":"1"}
    ]}}}}}"""
            )
        )
    assertEquals(1, lessons.size)
    assertEquals("2026-11-10 09:50–11:25", lessons.single().timeText)
    assertTrue(lessons.single().hasReplay)
    assertFalse(lessons.single().copy(hasReplay = false).hasReplay)
  }

  @Test
  fun parsesPaginationAndMultipleVideoSegmentsWithoutInventingUrls() {
    val page =
        CourseReplayParser.courses(
            json(
                """{"code":0,"data":{"total":"13","list":[{"id":"1","title":"编译技术","realname":"教师","structure_name":"学院","term_name":"2026-20271"}]}}"""
            ),
            1,
        )
    assertTrue(page.hasNext)
    assertFalse(page.copy(page = 2).hasNext)
    assertEquals("教师", page.courses.single().teacher)
    val root =
        json(
            """{"code":0,"data":{"segment_video_list":{"0":{"teacher_list":{"preview_url":"https://resource.msa.buaa.edu.cn/teacher.mp4"},"student_list":{},"ppt_list":{"preview_url":"https://resource.msa.buaa.edu.cn/ppt.mp4"}},"1":{"teacher_list":{"preview_url":"https://resource.msa.buaa.edu.cn/part2.mp4"}}}}}"""
        )
    assertEquals(
        listOf("片段1 · 教师", "片段1 · 课件", "片段2 · 教师"),
        CourseReplayParser.videos(root).map { it.label },
    )
    assertFails {
      CourseReplayParser.videos(
          json(root.toString().replace("resource.msa.buaa.edu.cn", "example.com"))
      )
    }
    assertFails { CourseReplayParser.courses(json("""{"code":5002,"msg":"missing token"}"""), 1) }
  }

  @Test
  fun parsesPhpCookieAndRequiresPositivePlaybackAuthorization() {
    val cookie = """a:2:{i:0;s:6:"_token";i:1;s:10:"test-token";}"""
    assertEquals("test-token", CourseReplayParser.token(cookie.encodeURLParameter()))
    assertEquals("", CourseReplayParser.token("expired"))
    CourseReplayParser.requirePlaybackPermission(
        json("""{"code":200,"data":{"hasPermission":true}}""")
    )
    assertFails {
      CourseReplayParser.requirePlaybackPermission(
          json("""{"code":200,"data":{"hasPermission":false}}""")
      )
    }
    assertFails {
      CourseReplayParser.requirePlaybackPermission(
          json("""{"code":401,"data":{"hasPermission":true}}""")
      )
    }
  }

  @Test
  fun serviceUsesCurrentSessionAndStopsBeforeLoadingDeniedVideo() = runTest {
    localConnectionTestMutex.lock()
    try {
      withContext(Dispatchers.Default) {
        AccountStore.settings = MapSettings()
        cn.edu.ubaa.api.local.LocalAuthSessionStore.settings = MapSettings()
        cn.edu.ubaa.api.auth.LoginStatsReporter.reporter = {}
        LocalCookieStore.settings = MapSettings()
        ConnectionModeStore.settings = MapSettings()
        ConnectionRuntime.clearSelectedMode()
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        AccountStore.save(
            SavedAccount(UserData("测试", "123"), "123", "secret", ConnectionMode.DIRECT)
        )
        LocalCookieStore.storage()
            .addCookie(
                Url("$COURSE_REPLAY_SITE/"),
                Cookie("_token", """a:2:{i:0;s:6:"_token";i:1;s:10:"test-token";}""", path = "/"),
            )
        LocalUpstreamClientProvider.reset()
        val calls = mutableListOf<String>()
        var searchCalls = 0
        var ssoChecks = 0
        LocalUpstreamClientProvider.clientFactory = { follow ->
          HttpClient(
              MockEngine { request ->
                calls += request.url.encodedPath
                when {
                  request.url.encodedPath.contains("casapi") &&
                      request.url.parameters["r"] == "auth/login" -> {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location,
                            "http://yjapi.msa.buaa.edu.cn/casapi/index.php?r=auth/callback&ticket=ST-test%2Bvalue",
                        ),
                    )
                  }
                  request.url.encodedPath.contains("casapi") -> {
                    assertEquals(URLProtocol.HTTPS, request.url.protocol)
                    assertEquals("ST-test+value", request.url.parameters["ticket"])
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "$COURSE_REPLAY_SITE/courseCenter"),
                    )
                  }
                  request.url.encodedPath == "/courseCenter" -> respond("<html>智学北航</html>")
                  request.url.encodedPath.contains("study-auth") -> {
                    assertEquals("test-token", request.headers[HttpHeaders.Authorization])
                    assertEquals("123", request.headers["username"])
                    respond("""{"code":200,"data":{"hasPermission":false}}""")
                  }
                  request.url.encodedPath.endsWith("get-course-list") -> {
                    searchCalls++
                    if (searchCalls == 1) respond("""{"code":5002,"msg":"未登录"}""")
                    else respond("""{"code":0,"data":{"total":"0","list":[]}}""")
                  }
                  request.url.host == "sso.buaa.edu.cn" -> {
                    ssoChecks++
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://uc.buaa.edu.cn/"),
                    )
                  }
                  request.url.encodedPath == "/api/login" -> respond("<html>UC</html>")
                  request.url.encodedPath == "/api/uc/status" ->
                      respond("""{"code":0,"data":{"name":"测试","schoolid":"123"}}""")
                  else -> error("Must not request video after permission denial")
                }
              }
          ) {
            followRedirects = follow
          }
        }
        val service = CourseReplayService()
        assertTrue(service.videos("123", "12", "34").isFailure)
        assertEquals(4, calls.size)
        assertTrue(service.search("previous-account", "测试").isFailure)
        assertEquals(4, calls.size)
        assertTrue(service.search("123", "测试").isSuccess)
        assertEquals(2, searchCalls)
        assertEquals(1, ssoChecks)
        assertEquals("123", AccountStore.active()?.user?.schoolid)
      }
    } finally {
      cn.edu.ubaa.api.auth.LoginStatsReporter.reset()
      cn.edu.ubaa.api.local.LocalAuthSessionStore.settings = MapSettings()
      LocalUpstreamClientProvider.reset()
      LocalCookieStore.settings = MapSettings()
      AccountStore.settings = MapSettings()
      ConnectionRuntime.clearSelectedMode()
      localConnectionTestMutex.unlock()
    }
  }
}
