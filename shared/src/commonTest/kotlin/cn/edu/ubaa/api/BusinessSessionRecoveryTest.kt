package cn.edu.ubaa.api

import cn.edu.ubaa.api.auth.*
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.api.storage.SavedAccount
import cn.edu.ubaa.model.dto.*
import com.russhwolf.settings.MapSettings
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class BusinessSessionRecoveryTest {
  private val user = UserData("测试", "123")

  @BeforeTest
  fun setup() {
    runTest { localConnectionTestMutex.lock() }
    AccountStore.settings = MapSettings()
    ConnectionModeStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    AccountStore.save(SavedAccount(user, "123", "password", ConnectionMode.DIRECT))
  }

  @AfterTest
  fun cleanup() {
    AuthSessionCoordinator.clearVerification("123")
    AccountStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    localConnectionTestMutex.unlock()
  }

  private fun expired() = ApiCallException("expired", code = "business_session_expired")

  private inner class Auth : AuthService() {
    var preloads = 0
    var logins = 0
    var preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh")

    override suspend fun preloadLoginState(): Result<LoginPreloadResponse> {
      preloads++
      return Result.success(preload)
    }

    override suspend fun getAuthStatus(): Result<SessionStatusResponse> =
        error("UC status must not stand in for SSO")

    override suspend fun login(
        username: String,
        password: String,
        captcha: String?,
        execution: String?,
    ): Result<LoginResponse> {
      logins++
      assertEquals("123", username)
      assertEquals("password", password)
      assertEquals("fresh", execution)
      return Result.success(LoginResponse(user, "", "", "", ""))
    }
  }

  @Test
  fun concurrentExpiredBusinessSessionsRenewOnceWithoutLoggingOut() = runTest {
    val auth = Auth()
    var invalidations = 0
    val recovery = BusinessSessionRecovery(auth) { invalidations++ }
    val bothStarted = CompletableDeferred<Unit>()
    var starts = 0
    val calls =
        List(2) {
          async {
            var attempts = 0
            recovery
                .execute {
                  if (attempts++ == 0) {
                    if (++starts == 2) bothStarted.complete(Unit)
                    bothStarted.await()
                    Result.failure(expired())
                  } else Result.success("restored")
                }
                .getOrThrow()
          }
        }
    assertEquals(listOf("restored", "restored"), calls.awaitAll())
    assertEquals(1, auth.preloads)
    assertEquals(1, auth.logins)
    assertEquals(1, invalidations)
    assertEquals(user, AccountStore.active()?.user)
  }

  @Test
  fun validSsoRebuildsBusinessClientWithoutSubmittingPassword() = runTest {
    val auth = Auth().apply { preload = LoginPreloadResponse(false, userData = user) }
    var refreshed = false
    val recovery = BusinessSessionRecovery(auth) { refreshed = true }
    val result =
        recovery.execute { if (refreshed) Result.success(1) else Result.failure(expired()) }
    assertEquals(1, result.getOrThrow())
    assertEquals(0, auth.logins)
  }

  @Test
  fun webVpnRecoveryAlsoRefreshesDirectSsoUsedByTodoServices() = runTest {
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    ConnectionRuntime.resolveSelectedMode()
    AccountStore.save(SavedAccount(user, "123", "password", ConnectionMode.WEBVPN))
    val auth =
        Auth().apply { preload = LoginPreloadResponse(false, execution = "fresh", userData = user) }
    var refreshed = false
    val recovery = BusinessSessionRecovery(auth) { refreshed = true }
    assertTrue(
        recovery
            .execute { if (refreshed) Result.success(1) else Result.failure(expired()) }
            .isSuccess
    )
    assertEquals(1, auth.logins)
  }

  @Test
  fun networkFailureIsNotRetriedAndCancellationPropagates() = runTest {
    val auth = Auth()
    val recovery = BusinessSessionRecovery(auth) {}
    var calls = 0
    val failure = ApiCallException("network")
    assertSame(
        failure,
        recovery
            .execute<Int> {
              calls++
              Result.failure(failure)
            }
            .exceptionOrNull(),
    )
    assertEquals(1, calls)
    assertEquals(0, auth.preloads)
    assertFailsWith<CancellationException> {
      recovery.execute<Int> { throw CancellationException() }
    }
  }

  @Test
  fun secondAuthenticationFailureDoesNotLoop() = runTest {
    val auth = Auth()
    val recovery = BusinessSessionRecovery(auth) {}
    var calls = 0
    assertTrue(
        recovery
            .execute<Int> {
              calls++
              Result.failure(expired())
            }
            .isFailure
    )
    assertEquals(2, calls)
    assertEquals(1, auth.logins)
  }

  @Test
  fun accountChangeStopsRecoveryAndDiscardsOldResult() = runTest {
    val auth = Auth()
    val recovery = BusinessSessionRecovery(auth) {}
    val result =
        recovery.execute<Int> {
          AccountStore.save(
              SavedAccount(UserData("另一个", "456"), "456", "other", ConnectionMode.DIRECT)
          )
          Result.failure(expired())
        }
    assertTrue(result.isFailure)
    assertEquals(0, auth.logins)
    assertEquals("456", AccountStore.active()?.user?.schoolid)
  }

  @Test
  fun missingPasswordRequestsVerificationWithoutRemovingAccountOrRepeatingPreload() = runTest {
    AccountStore.save(SavedAccount(user, "123", null, ConnectionMode.DIRECT))
    val auth = Auth()
    val recovery = BusinessSessionRecovery(auth) {}
    repeat(2) { assertTrue(recovery.execute<Int> { Result.failure(expired()) }.isFailure) }
    assertEquals("123", AuthSessionCoordinator.verificationRequired.value?.owner)
    assertEquals(1, auth.preloads)
    assertEquals(0, auth.logins)
    assertEquals(user, AccountStore.active()?.user)
  }
}
