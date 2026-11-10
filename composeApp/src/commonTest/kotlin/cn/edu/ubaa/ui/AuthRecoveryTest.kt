package cn.edu.ubaa.ui

import cn.edu.ubaa.api.*
import cn.edu.ubaa.api.auth.*
import cn.edu.ubaa.api.storage.*
import cn.edu.ubaa.model.dto.*
import cn.edu.ubaa.repository.ScheduleStore
import cn.edu.ubaa.ui.screens.auth.AuthViewModel
import com.russhwolf.settings.MapSettings
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRecoveryTest {
  @BeforeTest
  fun setup() {
    AccountStore.settings = MapSettings()
    CredentialStore.settings = MapSettings()
    ScheduleStore.settings = MapSettings()
    AuthTokensStore.settings = MapSettings()
    ConnectionModeStore.settings = MapSettings()
    ConnectionRuntime.clearSelectedMode()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
  }

  @AfterTest
  fun cleanup() {
    Dispatchers.resetMain()
    ConnectionRuntime.clearSelectedMode()
  }

  private fun credentials() {
    CredentialStore.saveCredentials("111", "saved-password")
    CredentialStore.setRememberPassword(true)
    CredentialStore.setAutoLogin(true)
  }

  @Test
  fun autoLoginPreloadsAndCaptchaAlwaysFinishesStartup() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    credentials()
    val auth =
        FakeAuth().apply {
          preload =
              LoginPreloadResponse(
                  true,
                  captcha = CaptchaInfo(id = "captcha", imageUrl = "url"),
                  execution = "fresh",
              )
        }
    val vm = AuthViewModel(auth)
    vm.initializeApp()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isInitialized)
    assertTrue(vm.uiState.value.captchaRequired)
    assertFalse(vm.uiState.value.isLoading)
    assertEquals(listOf("preload"), auth.calls)
    vm.updateCaptcha("1234")
    vm.login()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isLoggedIn)
    assertEquals("fresh", auth.execution)
  }

  @Test
  fun transientStartupAndResumeErrorsNeverClearValidSession() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    credentials()
    AccountStore.save(
        SavedAccount(UserData("甲", "111"), "111", "saved-password", ConnectionMode.DIRECT)
    )
    val auth =
        FakeAuth().apply {
          persisted = true
          statusError = ApiCallException("网络暂不可用", HttpStatusCode.ServiceUnavailable)
        }
    val vm = AuthViewModel(auth)
    vm.initializeApp()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isLoggedIn)
    assertFalse(auth.cleared)
    vm.validateSession()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isLoggedIn)
    assertFalse(auth.cleared)
    assertEquals(0, auth.calls.count { it == "login" })
  }

  @Test
  fun expiredSessionRecoversWithSavedCredentialsAndFreshExecution() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    credentials()
    val auth =
        FakeAuth().apply {
          persisted = true
          statusError = ApiCallException("过期", HttpStatusCode.Unauthorized)
        }
    val vm = AuthViewModel(auth)
    vm.initializeApp()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isLoggedIn)
    assertTrue(auth.cleared)
    assertEquals(listOf("status", "preload", "login"), auth.calls)
    assertEquals("saved-password", auth.password)
    assertEquals("new-execution", auth.execution)
  }

  @Test
  fun duplicateInitializationIsSingleFlightAndTimeoutIsRecoverable() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val auth = FakeAuth().apply { hang = true }
    val vm = AuthViewModel(auth)
    vm.initializeApp()
    vm.initializeApp()
    runCurrent()
    assertEquals(listOf("preload"), auth.calls)
    advanceTimeBy(60_001)
    runCurrent()
    assertTrue(vm.uiState.value.isInitialized)
    assertFalse(vm.uiState.value.isLoading)
    assertNotNull(vm.uiState.value.error)
    auth.hang = false
    vm.preloadLoginState()
    advanceUntilIdle()
    assertNull(vm.uiState.value.error)
    assertEquals(2, auth.calls.size)
  }

  @Test
  fun reopeningKeepsAccountAndBackgroundSettingWhileSessionRecovers() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    credentials()
    val first = AuthViewModel(FakeAuth())
    first.initializeApp()
    advanceUntilIdle()
    cn.edu.ubaa.attendance.AttendanceStore.settings = MapSettings()
    cn.edu.ubaa.attendance.AttendanceStore.setEnabled("111", true)
    val reconnect = FakeAuth().apply { hang = true }
    val reopened = AuthViewModel(reconnect)
    reopened.initializeApp()
    assertTrue(reopened.uiState.value.isLoggedIn)
    advanceTimeBy(60_001)
    runCurrent()
    assertTrue(reopened.uiState.value.isLoggedIn)
    assertEquals("111", AccountStore.active()?.user?.schoolid)
    assertTrue(cn.edu.ubaa.attendance.AttendanceStore.enabled("111"))
    reconnect.hang = false
    reopened.initializeApp()
    advanceUntilIdle()
    assertTrue(reopened.uiState.value.isLoggedIn)
    assertEquals("saved-password", reconnect.password)
  }

  @Test
  fun accountSwitchUsesSelectedCredentialsAndRemovalStopsActiveAccount() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    credentials()
    AccountStore.save(
        SavedAccount(UserData("乙", "222"), "222", "second-password", ConnectionMode.DIRECT)
    )
    val auth = FakeAuth()
    val vm = AuthViewModel(auth)
    vm.selectAccount("222")
    advanceUntilIdle()
    assertEquals("222", vm.uiState.value.userData?.schoolid)
    assertEquals("second-password", auth.password)
    assertEquals("222", ScheduleStore.account())
    assertEquals("222", AccountStore.active()?.user?.schoolid)
    vm.removeAccount("222")
    advanceUntilIdle()
    assertNull(AccountStore.active())
    assertNull(ScheduleStore.account())
    assertFalse(vm.uiState.value.isLoggedIn)
  }

  private class FakeAuth : AuthService() {
    val calls = mutableListOf<String>()
    var persisted = false
    var cleared = false
    var hang = false
    var preload = LoginPreloadResponse(false, execution = "new-execution")
    var statusError: Exception? = null
    var password: String? = null
    var execution: String? = null

    override fun hasPersistedSession() = persisted

    override fun applyStoredTokens() = Unit

    override fun clearStoredSession() {
      cleared = true
      persisted = false
    }

    override suspend fun preloadLoginState(): Result<LoginPreloadResponse> {
      calls += "preload"
      if (hang) awaitCancellation()
      return Result.success(preload)
    }

    override suspend fun getAuthStatus(): Result<SessionStatusResponse> {
      calls += "status"
      return statusError?.let { Result.failure(it) }
          ?: Result.success(SessionStatusResponse(UserData("甲", "111"), "", ""))
    }

    override suspend fun login(
        username: String,
        password: String,
        captcha: String?,
        execution: String?,
    ): Result<LoginResponse> {
      calls += "login"
      this.password = password
      this.execution = execution
      return Result.success(LoginResponse(UserData("测试", username), "access", "refresh", "", ""))
    }
  }
}
