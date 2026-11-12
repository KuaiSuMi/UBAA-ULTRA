package cn.edu.ubaa.api.auth

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

data class SessionVerification(val owner: String, val error: Throwable)

/** UI 和后台任务共享同一组 Cookie，认证流程必须串行执行。 */
object AuthSessionCoordinator {
  val mutex = Mutex()
  private val verification = MutableStateFlow<SessionVerification?>(null)
  val verificationRequired = verification.asStateFlow()
  private val restored = MutableStateFlow(0L)
  val restorations = restored.asStateFlow()

  fun requireVerification(owner: String, error: Throwable) {
    verification.value = SessionVerification(owner, error)
  }

  fun sessionRestored(owner: String) {
    cn.edu.ubaa.api.core.DefaultApiFactory.clearCachedBackends()
    clearVerification(owner)
  }

  fun clearVerification(owner: String) {
    if (verification.value?.owner == owner) {
      verification.value = null
      restored.update { it + 1 }
    }
  }
}

fun Throwable.isExpiredSession(): Boolean =
    this is ApiCallException &&
        (status == HttpStatusCode.Unauthorized ||
            code in
                setOf(
                    "invalid_token",
                    "invalid_refresh_token",
                    "unauthenticated",
                    "unauthorized",
                    "business_session_expired",
                    "ihome_auth",
                ))
