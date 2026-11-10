package cn.edu.ubaa.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.api.ConnectionRuntime
import cn.edu.ubaa.api.auth.*
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.api.storage.AuthTokensStore
import cn.edu.ubaa.api.storage.CredentialStore
import cn.edu.ubaa.api.storage.SavedAccount
import cn.edu.ubaa.model.dto.CaptchaInfo
import cn.edu.ubaa.model.dto.UserData
import cn.edu.ubaa.model.dto.UserInfo
import cn.edu.ubaa.repository.ScheduleStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

class AuthViewModel(
    private val authService: AuthService = AuthService(),
    private val userService: UserService = UserService(),
) : ViewModel() {
  private var authJob: Job? = null
  private var authGeneration = 0L
  private var userInfoJob: Job? = null
  private val _uiState = MutableStateFlow(AuthUiState())
  val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
  private val _loginForm = MutableStateFlow(LoginFormState())
  val loginForm: StateFlow<LoginFormState> = _loginForm.asStateFlow()

  init {
    loadSavedCredentials()
  }

  private fun loadSavedCredentials() {
    val remember = CredentialStore.isRememberPassword() || CredentialStore.getUsername() == null
    _loginForm.value =
        LoginFormState(
            username = if (remember) CredentialStore.getUsername().orEmpty() else "",
            password = if (remember) CredentialStore.getPassword().orEmpty() else "",
            rememberPassword = remember,
            autoLogin =
                remember &&
                    (CredentialStore.isAutoLogin() || CredentialStore.getUsername() == null),
        )
  }

  fun updateUsername(username: String) {
    _loginForm.value = _loginForm.value.copy(username = username)
  }

  fun updatePassword(password: String) {
    _loginForm.value = _loginForm.value.copy(password = password)
  }

  fun updateCaptcha(captcha: String) {
    _loginForm.value = _loginForm.value.copy(captcha = captcha)
  }

  fun updateRememberPassword(enabled: Boolean) {
    _loginForm.value =
        _loginForm.value.copy(
            rememberPassword = enabled,
            autoLogin = enabled && _loginForm.value.autoLogin,
        )
  }

  fun updateAutoLogin(enabled: Boolean) {
    _loginForm.value =
        _loginForm.value.copy(
            autoLogin = enabled,
            rememberPassword = enabled || _loginForm.value.rememberPassword,
        )
  }

  // 一次操作覆盖完整预加载/登录流程，验证码、超时和异常都能离开启动页。
  private fun authenticate(replace: Boolean = false, block: suspend () -> Unit) {
    if (authJob?.isActive == true && !replace) return
    val generation = ++authGeneration
    if (replace) authJob?.cancel()
    authJob =
        viewModelScope.launch {
          _uiState.value = _uiState.value.copy(isLoading = true, error = null)
          try {
            withTimeout(60_000) { AuthSessionCoordinator.mutex.withLock { block() } }
          } catch (e: TimeoutCancellationException) {
            if (generation == authGeneration)
                _uiState.value = _uiState.value.copy(error = "登录请求超时，请重试或切换连接模式")
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            if (generation == authGeneration)
                _uiState.value = _uiState.value.copy(error = e.message ?: "登录失败，请重试")
          } finally {
            if (generation == authGeneration)
                _uiState.value =
                    _uiState.value.copy(
                        isInitialized = true,
                        isLoading = false,
                        isPreloading = false,
                        isRefreshingCaptcha = false,
                    )
          }
        }
  }

  private fun acceptSession(user: UserData, token: String? = null) {
    userInfoJob?.cancel()
    ScheduleStore.useAccount(user.schoolid)
    val form = _loginForm.value
    val saved = AccountStore.get(user.schoolid)
    AccountStore.save(
        SavedAccount(
            user,
            form.username.ifBlank { saved?.username ?: user.schoolid },
            if (form.rememberPassword) form.password.takeIf { it.isNotBlank() } ?: saved?.password
            else null,
            ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT,
        )
    )
    _uiState.value =
        AuthUiState(
            isInitialized = true,
            isLoggedIn = true,
            userData = user,
            accessToken = token?.takeIf { it.isNotBlank() },
        )
    _loginForm.value = _loginForm.value.copy(captcha = "")
  }

  private suspend fun preload(): Boolean {
    _uiState.value = _uiState.value.copy(isPreloading = true)
    val response = authService.preloadLoginState().getOrThrow()
    currentCoroutineContext().ensureActive()
    if (response.userData != null) {
      acceptSession(response.userData!!, response.accessToken)
    } else {
      _uiState.value =
          _uiState.value.copy(
              captchaRequired = response.captchaRequired,
              captchaInfo = response.captcha,
              execution = response.execution,
              isLoggedIn = _uiState.value.isLoggedIn && !response.captchaRequired,
          )
      _loginForm.value = _loginForm.value.copy(captcha = "")
    }
    return response.userData != null
  }

  fun preloadLoginState() = authenticate { preload() }

  fun refreshCaptcha() = authenticate {
    _uiState.value = _uiState.value.copy(isRefreshingCaptcha = true)
    preload()
  }

  private suspend fun submitLogin() {
    val form = _loginForm.value
    require(form.username.isNotBlank() && form.password.isNotBlank()) { "用户名和密码不能为空" }
    val state = _uiState.value
    if (state.captchaRequired && form.captcha.isBlank()) {
      _uiState.value = _uiState.value.copy(error = "请输入验证码")
      return
    }
    authService
        .login(
            form.username.trim(),
            form.password,
            form.captcha.takeIf { state.captchaRequired },
            state.execution,
        )
        .onSuccess { response ->
          currentCoroutineContext().ensureActive()
          acceptSession(response.user, response.accessToken)
          CredentialStore.setRememberPassword(form.rememberPassword)
          CredentialStore.setAutoLogin(form.autoLogin)
          if (form.rememberPassword)
              CredentialStore.saveCredentials(form.username.trim(), form.password)
          else _loginForm.value = LoginFormState()
        }
        .onFailure { error ->
          currentCoroutineContext().ensureActive()
          if (error is CaptchaRequiredClientException) {
            _uiState.value =
                _uiState.value.copy(
                    captchaRequired = true,
                    captchaInfo = error.captcha,
                    execution = error.execution,
                    error = error.message,
                    isLoggedIn = false,
                )
            _loginForm.value = _loginForm.value.copy(captcha = "")
          } else {
            // execution 是一次性流程标识，失败后下次提交重新取登录页。
            _uiState.value = _uiState.value.copy(execution = null, error = error.message ?: "登录失败")
          }
        }
  }

  fun login() = authenticate {
    if (_uiState.value.execution == null && !_uiState.value.captchaRequired) preload()
    if (!_uiState.value.isLoggedIn) submitLogin()
  }

  private suspend fun prepareLogin() {
    loadSavedCredentials()
    val restored = preload()
    if (
        !restored &&
            _loginForm.value.autoLogin &&
            _loginForm.value.username.isNotBlank() &&
            _loginForm.value.password.isNotBlank()
    )
        submitLogin()
  }

  fun initializeApp() {
    if (ConnectionRuntime.currentMode() == null) return
    authenticate {
      loadSavedCredentials()
      AccountStore.active()
          ?.takeIf { it.mode == ConnectionRuntime.currentMode() }
          ?.let { acceptSession(it.user, AuthTokensStore.get()?.accessToken) }
      if (!authService.hasPersistedSession()) {
        prepareLogin()
      } else {
        authService.applyStoredTokens()
        authService
            .getAuthStatus()
            .onSuccess {
              currentCoroutineContext().ensureActive()
              acceptSession(it.user, AuthTokensStore.get()?.accessToken)
            }
            .onFailure {
              currentCoroutineContext().ensureActive()
              if (!it.isExpiredSession()) {
                (AccountStore.active()?.user
                        ?: cn.edu.ubaa.api.local.LocalAuthSessionStore.get()?.user)
                    ?.let { user -> acceptSession(user, AuthTokensStore.get()?.accessToken) }
                throw it
              }
              authService.clearStoredSession()
              prepareLogin()
            }
      }
    }
  }

  fun switchConnectionMode(mode: ConnectionMode) =
      authenticate(replace = true) {
        userInfoJob?.cancel()
        val autoLogin = CredentialStore.isAutoLogin()
        ConnectionRuntime.switchMode(mode)
        CredentialStore.setAutoLogin(autoLogin)
        _uiState.value = AuthUiState(isLoading = true)
        prepareLogin()
      }

  fun logout() =
      authenticate(replace = true) {
        userInfoJob?.cancel()
        CredentialStore.setAutoLogin(false)
        AccountStore.deactivate()
        ScheduleStore.forgetAccount()
        try {
          authService.logout()
        } finally {
          authService.clearStoredSession()
          _uiState.value = AuthUiState(isLoading = true)
          loadSavedCredentials()
        }
        preload()
      }

  fun selectAccount(id: String?) =
      authenticate(replace = true) {
        val account = id?.let { requireNotNull(AccountStore.get(it)) { "账号已移除" } }
        userInfoJob?.cancel()
        ConnectionRuntime.resetSession()
        AccountStore.deactivate()
        ScheduleStore.forgetAccount()
        CredentialStore.clear()
        _uiState.value = AuthUiState(isLoading = true)
        if (account != null) {
          ConnectionRuntime.switchMode(account.mode)
          _loginForm.value =
              LoginFormState(
                  username = account.username,
                  password = account.password.orEmpty(),
                  rememberPassword = true,
                  autoLogin = true,
              )
        } else {
          _loginForm.value = LoginFormState(rememberPassword = true, autoLogin = true)
        }
        preload()
        if (account?.password != null && !_uiState.value.isLoggedIn) submitLogin()
      }

  fun removeAccount(id: String) {
    if (_uiState.value.userData?.schoolid == id) {
      authenticate(replace = true) {
        userInfoJob?.cancel()
        ConnectionRuntime.resetSession()
        CredentialStore.clear()
        ScheduleStore.forgetAccount()
        AccountStore.remove(id)
        _uiState.value = AuthUiState(isLoading = true)
        _loginForm.value = LoginFormState(rememberPassword = true, autoLogin = true)
        preload()
      }
    } else AccountStore.remove(id)
  }

  fun ensureUserInfoLoaded(forceRefresh: Boolean = false) {
    if (userInfoJob?.isActive == true || (!forceRefresh && _uiState.value.userInfo != null)) return
    val owner = _uiState.value.userData?.schoolid ?: return
    userInfoJob =
        viewModelScope.launch {
          userService.getUserInfo().onSuccess {
            if (_uiState.value.userData?.schoolid == owner)
                _uiState.value = _uiState.value.copy(userInfo = it)
          }
        }
  }

  fun validateSession() {
    if (!_uiState.value.isLoggedIn) return
    authenticate {
      authService
          .getAuthStatus()
          .onSuccess {
            currentCoroutineContext().ensureActive()
            _uiState.value = _uiState.value.copy(userData = it.user)
          }
          .onFailure {
            currentCoroutineContext().ensureActive()
            if (!it.isExpiredSession()) return@onFailure
            authService.clearStoredSession()
            userInfoJob?.cancel()
            prepareLogin()
          }
    }
  }

  fun clearError() {
    _uiState.value = _uiState.value.copy(error = null)
  }
}

data class AuthUiState(
    val isInitialized: Boolean = false,
    val isLoading: Boolean = false,
    val isPreloading: Boolean = false,
    val isRefreshingCaptcha: Boolean = false,
    val isLoggedIn: Boolean = false,
    val userData: UserData? = null,
    val userInfo: UserInfo? = null,
    val accessToken: String? = null,
    val error: String? = null,
    val captchaRequired: Boolean = false,
    val captchaInfo: CaptchaInfo? = null,
    val execution: String? = null,
)

data class LoginFormState(
    val username: String = "",
    val password: String = "",
    val captcha: String = "",
    val rememberPassword: Boolean = false,
    val autoLogin: Boolean = false,
)
