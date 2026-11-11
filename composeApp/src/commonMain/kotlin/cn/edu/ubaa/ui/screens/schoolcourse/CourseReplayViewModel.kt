package cn.edu.ubaa.ui.screens.schoolcourse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.ubaa.api.feature.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CourseReplayState(
    val keyword: String = "",
    val term: String = "",
    val terms: List<ReplayTerm> = emptyList(),
    val result: ReplayCoursePage? = null,
    val course: ReplayCourse? = null,
    val lessons: List<CourseLesson>? = null,
    val lesson: CourseLesson? = null,
    val videos: List<ReplayVideo>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class CourseReplayViewModel(
    private val owner: String,
    keyword: String = "",
    private val service: CourseReplayService = CourseReplayService(),
) : ViewModel() {
  private val _state = MutableStateFlow(CourseReplayState(keyword = keyword))
  val state = _state.asStateFlow()
  private var job: Job? = null

  fun search(
      keyword: String = state.value.keyword,
      term: String = state.value.term,
      page: Int = 1,
  ) {
    job?.cancel()
    _state.value =
        state.value.copy(
            keyword = keyword,
            term = term,
            result = null,
            course = null,
            lessons = null,
            lesson = null,
            videos = null,
            loading = true,
            error = null,
        )
    job =
        viewModelScope.launch {
          if (state.value.terms.isEmpty())
              service.terms(owner).onSuccess { _state.value = state.value.copy(terms = it) }
          service
              .search(owner, keyword, term, page)
              .onSuccess { _state.value = state.value.copy(result = it, loading = false) }
              .onFailure { fail(it) }
        }
  }

  fun openCourse(course: ReplayCourse) {
    job?.cancel()
    _state.value = state.value.copy(course = course, lessons = null, loading = true, error = null)
    job =
        viewModelScope.launch {
          service
              .lessons(owner, course.id)
              .onSuccess { _state.value = state.value.copy(lessons = it, loading = false) }
              .onFailure { fail(it) }
        }
  }

  fun play(lesson: CourseLesson) {
    val course = state.value.course ?: return
    if (!lesson.hasReplay) return
    job?.cancel()
    _state.value = state.value.copy(lesson = lesson, videos = null, loading = true, error = null)
    job =
        viewModelScope.launch {
          service
              .videos(owner, course.id, lesson.id)
              .onSuccess { _state.value = state.value.copy(videos = it, loading = false) }
              .onFailure { fail(it) }
        }
  }

  fun retry() {
    state.value.let {
      when {
        it.lesson != null -> play(it.lesson)
        it.course != null -> openCourse(it.course)
        else -> search()
      }
    }
  }

  fun back() {
    job?.cancel()
    _state.value =
        if (state.value.lesson != null)
            state.value.copy(lesson = null, videos = null, loading = false, error = null)
        else state.value.copy(course = null, lessons = null, loading = false, error = null)
  }

  private fun fail(error: Throwable) {
    _state.value = state.value.copy(loading = false, error = error.message ?: "课程加载失败，请重试")
  }
}
