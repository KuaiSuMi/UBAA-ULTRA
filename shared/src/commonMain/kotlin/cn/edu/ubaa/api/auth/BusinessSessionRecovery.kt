package cn.edu.ubaa.api.auth

import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.api.ConnectionRuntime
import cn.edu.ubaa.api.core.DefaultApiFactory
import cn.edu.ubaa.api.storage.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

private val businessSessionRecovery = BusinessSessionRecovery()

/** 仅在业务端明确拒绝旧会话时续登；网络超时和提交结果不明的请求不会重放。 */
suspend fun <T> withSessionRecovery(block: suspend () -> Result<T>): Result<T> =
    businessSessionRecovery.execute(block)

internal class BusinessSessionRecovery(
    private val auth: AuthService = AuthService(),
    private val invalidateClients: () -> Unit = DefaultApiFactory::clearCachedBackends,
) {
  // 与 UI 登录、账号切换共用锁；并发失效请求只续登一次。
  private var generation = 0L

  suspend fun <T> execute(block: suspend () -> Result<T>): Result<T> {
    val account = AccountStore.active()
    val mode = ConnectionRuntime.currentMode()
    if (account == null || mode == ConnectionMode.SERVER_RELAY) return block()
    val owner = account.user.schoolid
    fun checkOwner() {
      check(
          AccountStore.active()?.user?.schoolid == owner && ConnectionRuntime.currentMode() == mode
      ) {
        "账号已切换，请刷新页面"
      }
    }
    return try {
      val started =
          AuthSessionCoordinator.mutex.withLock {
            checkOwner()
            generation
          }
      val result = block()
      checkOwner()
      if (result.exceptionOrNull()?.isExpiredSession() != true) return result
      AuthSessionCoordinator.mutex.withLock {
        checkOwner()
        if (generation == started) {
          AuthSessionCoordinator.verificationRequired.value
              ?.takeIf { it.owner == owner }
              ?.let { throw it.error }
          try {
            // UC 与 SSO 的有效期不同，必须直接检查统一认证登录页。
            val preload = auth.preloadLoginState().getOrThrow()
            val user =
                preload.userData?.takeUnless {
                  mode == ConnectionMode.WEBVPN && !account.password.isNullOrBlank()
                }
                    ?: run {
                      if (preload.captchaRequired) {
                        throw CaptchaRequiredClientException(
                            requireNotNull(preload.captcha),
                            preload.execution.orEmpty(),
                            "请输入验证码完成登录验证",
                        )
                      }
                      val password =
                          AccountStore.active()?.password
                              ?: throw ApiCallException("请补充密码完成登录验证", code = "unauthenticated")
                      auth
                          .login(account.username, password, execution = preload.execution)
                          .getOrThrow()
                          .user
                    }
            checkOwner()
            check(user.schoolid == owner) { "统一认证账号与当前账号不一致" }
            invalidateClients()
            generation++
            AuthSessionCoordinator.clearVerification(owner)
          } catch (e: Exception) {
            if (
                e is CaptchaRequiredClientException ||
                    e.isExpiredSession() ||
                    (e is ApiCallException && e.code == "invalid_credentials")
            ) {
              AuthSessionCoordinator.requireVerification(owner, e)
            }
            throw e
          }
        }
      }
      checkOwner()
      block().also { checkOwner() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
