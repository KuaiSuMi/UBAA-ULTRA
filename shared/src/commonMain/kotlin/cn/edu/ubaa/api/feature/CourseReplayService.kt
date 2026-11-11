package cn.edu.ubaa.api.feature

import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.api.ConnectionRuntime
import cn.edu.ubaa.api.auth.ApiCallException
import cn.edu.ubaa.api.auth.AuthSessionCoordinator
import cn.edu.ubaa.api.local.*
import cn.edu.ubaa.api.storage.AccountStore
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

const val COURSE_REPLAY_SITE = "https://classroom.msa.buaa.edu.cn"
const val COURSE_REPLAY_PAGE_SIZE = 12

data class ReplayTerm(val id: String, val name: String)

data class ReplayCourse(
    val id: String,
    val name: String,
    val teacher: String,
    val department: String,
    val term: String,
)

data class ReplayCoursePage(val courses: List<ReplayCourse>, val total: Int, val page: Int) {
  val hasNext: Boolean
    get() = page * COURSE_REPLAY_PAGE_SIZE < total
}

data class CourseLesson(
    val id: String,
    val title: String,
    val teacher: String,
    val room: String,
    val begin: Long,
    val end: Long,
    val hasReplay: Boolean,
) {
  val timeText: String
    get() {
      if (begin <= 0 || end <= 0) return "时间未安排"
      val zone = TimeZone.of("Asia/Shanghai")
      val start = Instant.fromEpochSeconds(begin).toLocalDateTime(zone)
      val finish = Instant.fromEpochSeconds(end).toLocalDateTime(zone)
      return "${start.date} ${start.time.toString().take(5)}–${finish.time.toString().take(5)}"
    }
}

data class ReplayVideo(val label: String, val url: String)

open class CourseReplayService {
  open suspend fun terms(owner: String): Result<List<ReplayTerm>> =
      access(owner) { token ->
        val root = request("$COURSE_REPLAY_SITE/vlabpassportapi/v1/course/term-list", token)
        check(root.text("code") == "1000") { "学期列表获取失败" }
        root.getValue("params").jsonObject.getValue("list").jsonArray.map {
          ReplayTerm(it.jsonObject.text("id"), it.jsonObject.text("term_name"))
        }
      }

  open suspend fun search(
      owner: String,
      keyword: String,
      term: String = "",
      page: Int = 1,
  ): Result<ReplayCoursePage> =
      access(owner) { token ->
        require(page > 0)
        CourseReplayParser.courses(
            request(
                "$COURSE_REPLAY_SITE/courseapi/v3/multi-search/get-course-list",
                token,
                parametersOf(
                    "order" to listOf(""),
                    "title" to listOf(keyword.trim()),
                    "term" to listOf(term),
                    "publish" to listOf(""),
                    "page" to listOf(page.toString()),
                    "total" to listOf("0"),
                    "per_page" to listOf(COURSE_REPLAY_PAGE_SIZE.toString()),
                    "tenant" to listOf("21"),
                ),
            ),
            page,
        )
      }

  open suspend fun lessons(owner: String, courseId: String): Result<List<CourseLesson>> =
      access(owner) { token ->
        CourseReplayParser.lessons(
            request(
                "https://yjapi.msa.buaa.edu.cn/courseapi/v3/multi-search/get-course-detail",
                token,
                parametersOf("course_id" to listOf(courseId), "student" to listOf(owner)),
            )
        )
      }

  open suspend fun videos(
      owner: String,
      courseId: String,
      lessonId: String,
  ): Result<List<ReplayVideo>> =
      access(owner) { token ->
        require(courseId.all(Char::isDigit) && courseId.isNotEmpty())
        require(lessonId.all(Char::isDigit) && lessonId.isNotEmpty())
        val permission =
            request(
                "$COURSE_REPLAY_SITE/coursesourceapi/course/study-auth/$courseId/$lessonId",
                token,
                owner = owner,
            )
        CourseReplayParser.requirePlaybackPermission(permission)
        CourseReplayParser.videos(
                request(
                    "$COURSE_REPLAY_SITE/courseapi/v3/portal-home-setting/get-sub-info",
                    token,
                    parametersOf("course_id" to listOf(courseId), "sub_id" to listOf(lessonId)),
                )
            )
            .also { check(it.isNotEmpty()) { "本节课尚无回放视频" } }
      }

  private suspend fun <T> access(owner: String, block: suspend (String) -> T): Result<T> =
      try {
        withTimeout(30_000) {
          AuthSessionCoordinator.mutex.withLock {
            check(AccountStore.active()?.user?.schoolid == owner) { "当前账号已切换，请重新打开课程回放" }
            check(ConnectionRuntime.currentMode() != ConnectionMode.SERVER_RELAY) {
              "请使用直连或 WebVPN 查询课程回放"
            }
            val login =
                LocalUpstreamClientProvider.shared()
                    .get(
                        localUpstreamUrl(
                            "https://yjapi.msa.buaa.edu.cn/casapi/index.php?r=auth/login&auType=cmc&tenant_code=21&forward=${"$COURSE_REPLAY_SITE/courseCenter".encodeURLParameter()}"
                        )
                    )
            if (
                login.status != HttpStatusCode.OK ||
                    localIsSsoUrl(login.call.request.url.toString())
            ) {
              throw ApiCallException("请返回主页完成统一认证验证", code = "unauthenticated")
            }
            val cookie =
                LocalCookieStore.storage()
                    .get(Url(localUpstreamUrl("$COURSE_REPLAY_SITE/")))
                    .firstOrNull { it.name == "_token" }
                    ?.value
                    .orEmpty()
            val token = CourseReplayParser.token(cookie)
            check(token.isNotBlank()) { "智学北航登录未完成，请返回主页完成统一认证验证" }
            val data = block(token)
            check(AccountStore.active()?.user?.schoolid == owner) { "当前账号已切换" }
            Result.success(data)
          }
        }
      } catch (e: TimeoutCancellationException) {
        Result.failure(ApiCallException("智学北航连接超时，请重试", cause = e))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }

  private suspend fun request(
      url: String,
      token: String,
      query: Parameters = Parameters.Empty,
      owner: String? = null,
  ): JsonObject {
    val response =
        LocalUpstreamClientProvider.shared().get(localUpstreamUrl(url)) {
          header(HttpHeaders.Authorization, token)
          header(HttpHeaders.Referrer, "$COURSE_REPLAY_SITE/courseCenter")
          if (owner != null) {
            header("Tenant-Id", "21")
            header("username", owner)
          }
          url { parameters.appendAll(query) }
        }
    check(
        response.status == HttpStatusCode.OK && !localIsSsoUrl(response.call.request.url.toString())
    ) {
      "智学北航请求失败，请重新验证登录"
    }
    val body = response.bodyAsText()
    check(body.trimStart().startsWith("{")) { "智学北航会话已过期，请返回主页完成验证" }
    return Json.parseToJsonElement(body).jsonObject
  }
}

internal object CourseReplayParser {
  fun token(cookie: String): String =
      Regex("""s:\d+:"_token";i:\d+;s:\d+:"([^"]+)";""")
          .find(cookie.decodeURLPart())
          ?.groupValues
          ?.get(1)
          .orEmpty()

  fun courses(root: JsonObject, page: Int): ReplayCoursePage {
    val data = data(root)
    return ReplayCoursePage(
        data.getValue("list").jsonArray.map {
          val row = it.jsonObject
          ReplayCourse(
              row.text("id"),
              row.text("title"),
              row.text("realname"),
              row.text("structure_name"),
              row.text("term_name"),
          )
        },
        data.text("total").toInt(),
        page,
    )
  }

  fun lessons(root: JsonObject): List<CourseLesson> {
    val list = data(root).getValue("sub_list")
    fun flatten(node: JsonElement): List<JsonObject> =
        when (node) {
          is JsonArray -> node.map { it.jsonObject }
          is JsonObject -> node.values.flatMap(::flatten)
          else -> emptyList()
        }
    return flatten(list)
        .filter { it.text("show") == "yes" && it.text("sub_delete") == "0" }
        .map { row ->
          CourseLesson(
              row.text("id"),
              row.text("sub_title"),
              row.text("lecturer_name"),
              row.text("room_name"),
              row.text("class_begin").toLongOrNull() ?: 0,
              row.text("class_over").toLongOrNull() ?: 0,
              row.text("playback_status") == "1",
          )
        }
        .sortedBy { it.begin }
  }

  fun requirePlaybackPermission(root: JsonObject) {
    check(
        root.text("code") == "200" &&
            root["data"]?.jsonObject?.get("hasPermission")?.jsonPrimitive?.booleanOrNull == true
    ) {
      "当前账号没有本节课的观看权限，可联系任课教师添加旁听生"
    }
  }

  fun videos(root: JsonObject): List<ReplayVideo> {
    val segments = data(root).getValue("segment_video_list")
    val rows =
        when (segments) {
          is JsonObject -> segments.values.toList()
          is JsonArray -> segments.toList()
          else -> error("回放视频数据格式错误")
        }
    return rows.flatMapIndexed { index, element ->
      val segment = element.jsonObject
      listOf("teacher_list" to "教师", "ppt_list" to "课件", "student_list" to "学生").mapNotNull {
          (key, label) ->
        val url = segment[key]?.jsonObject?.text("preview_url").orEmpty()
        if (url.isBlank()) null
        else {
          val parsed = Url(url)
          check(
              parsed.protocol == URLProtocol.HTTPS &&
                  (parsed.host == "msa.buaa.edu.cn" || parsed.host.endsWith(".msa.buaa.edu.cn"))
          ) {
            "回放地址无效"
          }
          ReplayVideo(if (rows.size > 1) "片段${index + 1} · $label" else label, url)
        }
      }
    }
  }

  private fun data(root: JsonObject): JsonObject {
    check(root.text("code") == "0") { "智学北航查询失败，请重试或重新验证登录" }
    return root.getValue("data").jsonObject
  }
}

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
