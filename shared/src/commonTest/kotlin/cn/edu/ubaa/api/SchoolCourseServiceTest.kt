package cn.edu.ubaa.api

import cn.edu.ubaa.api.feature.*
import cn.edu.ubaa.api.local.LocalUpstreamClientProvider
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.api.storage.SavedAccount
import cn.edu.ubaa.model.dto.UserData
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class SchoolCourseServiceTest {
  @BeforeTest
  fun setup() {
    runTest { localConnectionTestMutex.lock() }
    AccountStore.settings = MapSettings()
    ConnectionModeStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    AccountStore.save(SavedAccount(UserData("测试", "123"), "123", "secret", ConnectionMode.DIRECT))
    LocalUpstreamClientProvider.reset()
  }

  @AfterTest
  fun cleanup() {
    LocalUpstreamClientProvider.reset()
    AccountStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    localConnectionTestMutex.unlock()
  }

  @Test
  fun queryUsesServerPaginationAndEscapesCombinedFilters() = runTest {
    withContext(Dispatchers.Default) {
      val requests = mutableListOf<String>()
      val query =
          SchoolCourseQuery(
              "数学\"&",
              SchoolCourseField.NAME,
              SchoolCourseTerm("2026-2027-1", "2026秋季"),
          )
      LocalUpstreamClientProvider.clientFactory = { follow ->
        HttpClient(
            MockEngine { request ->
              requests += request.url.encodedPath
              if (request.url.encodedPath.endsWith("index.do")) respond("<html>教学大纲管理</html>")
              else {
                val form =
                    parseQueryString(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    )
                assertEquals("20", form["pageSize"])
                assertEquals("2", form["pageNumber"])
                val filters =
                    Json.parseToJsonElement(requireNotNull(form["querySetting"])).jsonArray
                assertEquals(
                    "m_value_equal",
                    filters[0].jsonObject["builder"]?.jsonPrimitive?.content,
                )
                assertEquals(query.keyword, filters[1].jsonObject["value"]?.jsonPrimitive?.content)
                respond(
                    """{"code":"0","datas":{"jxdgcxlb":{"pageNumber":2,"totalSize":41,"rows":[{"WID":"test-row","KCH":"B000001","KCM":"数学测试","XF":".5","XS":16,"XNXQDM_DISPLAY":"2026秋季","KKDWDM_DISPLAY":"测试学院","KCFZRXM":null}]}}}"""
                )
              }
            }
        ) {
          followRedirects = follow
        }
      }
      val result = SchoolCourseService().search("123", query, 2).getOrThrow()
      assertEquals(2, requests.size)
      assertEquals(41, result.total)
      assertTrue(result.hasNext)
      assertEquals(".5", result.courses.single().credits)
      assertEquals("16", result.courses.single().hours)
      assertEquals("", result.courses.single().teacher)
    }
  }

  @Test
  fun emptyResultsAreDifferentFromExpiredOrMalformedResponses() {
    assertTrue(
        SchoolCourseParser.page(
                """{"code":0,"datas":{"jxdgcxlb":{"pageNumber":1,"totalSize":0,"rows":[]}}}"""
            )
            .courses
            .isEmpty()
    )
    assertFails { SchoolCourseParser.page("<html>统一认证</html>") }
    assertFails { SchoolCourseParser.page("""{"code":"1","datas":{}}""") }
    assertFails { SchoolCourseParser.page("""{"code":"0","datas":{}}""") }
    val terms =
        SchoolCourseParser.terms(
            """{"code":"0","datas":{"code":{"rows":[{"id":"2026-2027-1","name":"2026秋季"}]}}}"""
        )
    assertEquals("2026-2027-1", terms.single().id)
  }

  @Test
  fun accountSwitchPreventsRequestsForThePreviousOwner() = runTest {
    LocalUpstreamClientProvider.clientFactory = { error("Must not send request") }
    val result = SchoolCourseService().search("previous", SchoolCourseQuery())
    assertTrue(result.isFailure)
    assertEquals("123", AccountStore.active()?.user?.schoolid)
  }
}
