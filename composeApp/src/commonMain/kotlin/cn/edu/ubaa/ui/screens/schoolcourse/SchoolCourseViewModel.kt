package cn.edu.ubaa.ui.screens.schoolcourse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.ubaa.api.feature.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SchoolCourseState(
    val terms: List<SchoolCourseTerm> = emptyList(),
    val query: SchoolCourseQuery = SchoolCourseQuery(),
    val result: SchoolCoursePage? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class SchoolCourseViewModel(
    private val owner: String,
    private val service: SchoolCourseService = SchoolCourseService(),
) : ViewModel() {
  private val _state = MutableStateFlow(SchoolCourseState())
  val state = _state.asStateFlow()

  fun search(query: SchoolCourseQuery = state.value.query, page: Int = 1) {
    if (state.value.loading) return
    _state.value = state.value.copy(query = query, loading = true, error = null, result = null)
    viewModelScope.launch {
      try {
        if (state.value.terms.isEmpty())
            service.terms(owner).onSuccess { _state.value = state.value.copy(terms = it) }
        service
            .search(owner, query, page)
            .onSuccess { _state.value = state.value.copy(result = it) }
            .onFailure { _state.value = state.value.copy(error = it.message ?: "课程查询失败，请重试") }
      } finally {
        _state.value = state.value.copy(loading = false)
      }
    }
  }
}
