package cn.edu.ubaa.ui.screens.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.ubaa.api.feature.SigninApi
import cn.edu.ubaa.attendance.AttendanceStore
import cn.edu.ubaa.attendance.attendanceDate
import cn.edu.ubaa.attendance.beijingDate
import cn.edu.ubaa.model.dto.SigninClassDto
import cn.edu.ubaa.model.dto.Week
import cn.edu.ubaa.repository.ScheduleStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 课堂签到模块 UI 状态。 */
data class SigninUiState(
    val isLoading: Boolean = false,
    val classes: List<SigninClassDto> = emptyList(),
    val error: String? = null,
    /** 签到操作的结果提示（如“签到成功”）。 */
    val signinResult: String? = null,
    /** 当前正在执行签到的课程 ID（用于显示局部加载状态）。 */
    val signingInCourseId: String? = null,
    val historyError: String? = null,
)

/** 管理课堂签到功能的 ViewModel。 */
class SigninViewModel(
    private val signinApi: SigninApi = SigninApi(),
) : ViewModel() {
  private var loadedOnce = false
  private var loadedDate: String? = null
  private var historyJob: Job? = null
  private val loadedHistoryDates = mutableSetOf<String>()

  private val _uiState = MutableStateFlow(SigninUiState())
  /** 签到状态流。 */
  val uiState: StateFlow<SigninUiState> = _uiState.asStateFlow()

  fun ensureTodayLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && loadedOnce && loadedDate == beijingDate()) return
    loadTodayClasses()
  }

  internal fun hasTodayLoaded(): Boolean = loadedOnce

  /** 拉取今日所有可签到的课堂。 */
  fun loadTodayClasses() {
    loadedOnce = true
    val date = beijingDate()
    val owner = ScheduleStore.account()
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isLoading = true, error = null)
      signinApi
          .getClasses(date)
          .onSuccess {
            if (it.code != 200) {
              loadedOnce = false
              _uiState.value = _uiState.value.copy(isLoading = false, error = it.message)
              return@onSuccess
            }
            if (owner != null && owner == ScheduleStore.account())
                AttendanceStore.sync(owner, it.data, date)
            loadedDate = date
            _uiState.value = _uiState.value.copy(isLoading = false, classes = it.data)
          }
          .onFailure {
            loadedOnce = false
            _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "加载课程失败")
          }
    }
  }

  fun loadWeekRecords(week: Week, forceRefresh: Boolean = false) {
    val owner = ScheduleStore.account() ?: return
    val dates =
        (1..7)
            .mapNotNull { attendanceDate(week, it)?.toString() }
            .filter { it <= beijingDate() && (forceRefresh || it !in loadedHistoryDates) }
    historyJob?.cancel()
    historyJob =
        viewModelScope.launch {
          _uiState.value = _uiState.value.copy(historyError = null)
          for (date in dates) {
            val response = signinApi.getClasses(date)
            currentCoroutineContext().ensureActive()
            if (ScheduleStore.account() != owner) return@launch
            response
                .onSuccess {
                  if (it.code == 200) {
                    AttendanceStore.sync(owner, it.data, date)
                    loadedHistoryDates += date
                  } else _uiState.value = _uiState.value.copy(historyError = it.message)
                }
                .onFailure {
                  _uiState.value = _uiState.value.copy(historyError = it.message ?: "签到记录加载失败")
                }
          }
        }
  }

  /** 提交签到请求。 */
  fun performSignin(courseId: String) {
    if (_uiState.value.signingInCourseId != null) return
    val owner = ScheduleStore.account()
    val date = beijingDate()
    val course = _uiState.value.classes.singleOrNull { it.courseId == courseId }
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(signingInCourseId = courseId, signinResult = null)
      signinApi
          .performSignin(courseId)
          .onSuccess { response ->
            _uiState.value =
                _uiState.value.copy(
                    signingInCourseId = null,
                    signinResult = if (response.success) "签到成功" else "签到失败: ${response.message}",
                )
            if (response.success) {
              if (owner != null && owner == ScheduleStore.account() && course != null)
                  AttendanceStore.sync(owner, listOf(course.copy(signStatus = 1)), date)
              loadedOnce = false
              ensureTodayLoaded(forceRefresh = true)
            }
          }
          .onFailure {
            _uiState.value =
                _uiState.value.copy(signingInCourseId = null, signinResult = "签到异常: ${it.message}")
          }
    }
  }

  /** 重置内部加载标记与 UI 状态，用于连接模式切换等场景。 */
  fun resetLoadedState() {
    historyJob?.cancel()
    loadedHistoryDates.clear()
    loadedDate = null
    loadedOnce = false
    _uiState.value = SigninUiState()
  }

  /** 清除当前的签到结果提示。 */
  fun clearSigninResult() {
    _uiState.value = _uiState.value.copy(signinResult = null)
  }
}
