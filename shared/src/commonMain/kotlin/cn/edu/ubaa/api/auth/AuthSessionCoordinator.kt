package cn.edu.ubaa.api.auth

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex

/** UI 和后台任务共享同一组 Cookie，认证流程必须串行执行。 */
object AuthSessionCoordinator {
  val mutex = Mutex()
}

fun Throwable.isExpiredSession(): Boolean =
    this is ApiCallException &&
        (status == HttpStatusCode.Unauthorized ||
            code in
                setOf("invalid_token", "invalid_refresh_token", "unauthenticated", "unauthorized"))
