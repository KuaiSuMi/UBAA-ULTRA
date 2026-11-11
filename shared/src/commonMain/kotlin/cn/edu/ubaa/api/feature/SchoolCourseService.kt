package cn.edu.ubaa.api.feature

import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.api.ConnectionRuntime
import cn.edu.ubaa.api.auth.ApiCallException
import cn.edu.ubaa.api.auth.AuthSessionCoordinator
import cn.edu.ubaa.api.local.*
import cn.edu.ubaa.api.storage.AccountStore
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

data class SchoolCourseTerm(val id: String, val name: String)

enum class SchoolCourseField(val key: String, val label: String) {
  NAME("KCM", "课程名"),
  CODE("KCH", "课程号"),
  TEACHER("KCFZRXM", "课程负责人姓名"),
}

data class SchoolCourseQuery(
    val keyword: String = "",
    val field: SchoolCourseField = SchoolCourseField.NAME,
    val term: SchoolCourseTerm? = null,
)

data class SchoolCourse(
    val id: String,
    val code: String,
    val name: String,
    val term: String,
    val department: String,
    val credits: String,
    val hours: String,
    val teacher: String,
    val englishName: String,
    val category: String,
    val exam: String,
    val language: String,
    val level: String,
)

data class SchoolCoursePage(val courses: List<SchoolCourse>, val page: Int, val total: Int) {
  val hasNext: Boolean
    get() = page * SCHOOL_COURSE_PAGE_SIZE < total
}

const val SCHOOL_COURSE_PAGE_SIZE = 20
private const val SCHOOL_COURSE_APP = "https://byxt.buaa.edu.cn/jwapp/sys/kcdgglbuaa"

open class SchoolCourseService {
  open suspend fun terms(owner: String): Result<List<SchoolCourseTerm>> =
      access(owner) {
        SchoolCourseParser.terms(
            post("https://byxt.buaa.edu.cn/jwapp/code/1daca2c6-abd2-4278-af49-5af038db0926.do")
        )
      }

  open suspend fun search(
      owner: String,
      query: SchoolCourseQuery,
      page: Int = 1,
  ): Result<SchoolCoursePage> =
      access(owner) {
        require(page > 0)
        SchoolCourseParser.page(
            post(
                "$SCHOOL_COURSE_APP/modules/jxdgcx/jxdgcxlb.do",
                Parameters.build {
                  append("querySetting", SchoolCourseParser.querySetting(query))
                  append("pageSize", SCHOOL_COURSE_PAGE_SIZE.toString())
                  append("pageNumber", page.toString())
                },
            )
        )
      }

  private suspend fun <T> access(owner: String, block: suspend () -> T): Result<T> =
      try {
        withTimeout(30_000) {
          AuthSessionCoordinator.mutex.withLock {
            check(AccountStore.active()?.user?.schoolid == owner) { "当前账号已切换，请重新打开课程查询" }
            check(ConnectionRuntime.currentMode() != ConnectionMode.SERVER_RELAY) {
              "请使用直连或 WebVPN 查询全校课程"
            }
            val response =
                LocalUpstreamClientProvider.shared()
                    .get(localUpstreamUrl("$SCHOOL_COURSE_APP/*default/index.do"))
            if (
                response.status == HttpStatusCode.Unauthorized ||
                    localIsSsoUrl(response.call.request.url.toString())
            ) {
              throw ApiCallException("请返回主页完成统一认证验证", code = "unauthenticated")
            }
            check(response.status == HttpStatusCode.OK) { "本研系统暂时无法连接" }
            val result = block()
            check(AccountStore.active()?.user?.schoolid == owner) { "当前账号已切换" }
            Result.success(result)
          }
        }
      } catch (e: TimeoutCancellationException) {
        Result.failure(ApiCallException("课程查询超时，请重试", cause = e))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }

  private suspend fun post(url: String, form: Parameters = Parameters.Empty): String {
    val response =
        LocalUpstreamClientProvider.shared().post(localUpstreamUrl(url)) {
          header("X-Requested-With", "XMLHttpRequest")
          header(HttpHeaders.Referrer, localUpstreamUrl("$SCHOOL_COURSE_APP/*default/index.do"))
          setBody(FormDataContent(form))
        }
    if (
        response.status == HttpStatusCode.Unauthorized ||
            localIsSsoUrl(response.call.request.url.toString())
    ) {
      throw ApiCallException("请返回主页完成统一认证验证", code = "unauthenticated")
    }
    check(response.status == HttpStatusCode.OK) { "课程查询失败（${response.status.value}）" }
    return response.bodyAsText()
  }
}

internal object SchoolCourseParser {
  fun querySetting(query: SchoolCourseQuery): String =
      buildJsonArray {
            query.term?.let { term ->
              add(
                  buildJsonObject {
                    put("name", "XNXQDM")
                    put("caption", "学年学期")
                    put("linkOpt", "AND")
                    put("builderList", "cbl_m_List")
                    put("builder", "m_value_equal")
                    put("value", term.id)
                    put("value_display", term.name)
                  }
              )
            }
            if (query.keyword.isNotBlank())
                add(
                    buildJsonObject {
                      put("name", query.field.key)
                      put("caption", query.field.label)
                      put("linkOpt", "AND")
                      put("builderList", "cbl_String")
                      put("builder", "include")
                      put("value", query.keyword.trim())
                    }
                )
          }
          .toString()

  fun terms(body: String): List<SchoolCourseTerm> =
      data(body, "code").getValue("rows").jsonArray.map {
        val row = it.jsonObject
        SchoolCourseTerm(row.text("id"), row.text("name"))
      }

  fun page(body: String): SchoolCoursePage {
    val data = data(body, "jxdgcxlb")
    return SchoolCoursePage(
        data.getValue("rows").jsonArray.map {
          val row = it.jsonObject
          SchoolCourse(
              row.text("WID"),
              row.text("KCH"),
              row.text("KCM"),
              row.text("XNXQDM_DISPLAY"),
              row.text("KKDWDM_DISPLAY"),
              row.text("XF"),
              row.text("XS"),
              row.text("KCFZRXM"),
              row.text("YWKCM"),
              row.text("KCFLDM_DISPLAY"),
              row.text("KSLXDM_DISPLAY"),
              row.text("SKYZDM_DISPLAY"),
              row.text("KCCCDM_DISPLAY"),
          )
        },
        data.getValue("pageNumber").jsonPrimitive.int,
        data.getValue("totalSize").jsonPrimitive.int,
    )
  }

  private fun data(body: String, key: String): JsonObject {
    check(body.trimStart().startsWith("{")) { "本研系统会话已过期，请返回主页完成验证" }
    val root = Json.parseToJsonElement(body).jsonObject
    check(root.text("code") == "0") { "本研系统课程查询失败" }
    return root.getValue("datas").jsonObject.getValue(key).jsonObject
  }

  private fun JsonObject.text(key: String): String =
      this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
