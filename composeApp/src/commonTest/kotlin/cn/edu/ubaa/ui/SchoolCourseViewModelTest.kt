package cn.edu.ubaa.ui

import cn.edu.ubaa.api.feature.*
import cn.edu.ubaa.ui.screens.schoolcourse.SchoolCourseViewModel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SchoolCourseViewModelTest {
  @AfterTest
  fun cleanup() {
    Dispatchers.resetMain()
  }

  @Test
  fun failedSearchClearsOldResultsAndCanRetryWithCurrentFilter() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val service =
        object : SchoolCourseService() {
          var fail = false
          val requests = mutableListOf<Pair<SchoolCourseQuery, Int>>()

          override suspend fun terms(owner: String) =
              Result.success(listOf(SchoolCourseTerm("term", "学期")))

          override suspend fun search(
              owner: String,
              query: SchoolCourseQuery,
              page: Int,
          ): Result<SchoolCoursePage> {
            requests += query to page
            return if (fail) Result.failure(IllegalStateException("查询失败"))
            else Result.success(SchoolCoursePage(emptyList(), page, 45))
          }
        }
    val model = SchoolCourseViewModel("owner", service)
    model.search()
    assertEquals(45, model.state.value.result?.total)
    service.fail = true
    val query = SchoolCourseQuery("测试", SchoolCourseField.TEACHER)
    model.search(query)
    assertNull(model.state.value.result)
    assertNotNull(model.state.value.error)
    assertFalse(model.state.value.loading)
    service.fail = false
    model.search(model.state.value.query, 2)
    assertEquals(query to 2, service.requests.last())
    assertEquals(2, model.state.value.result?.page)
    assertNull(model.state.value.error)
  }
}
