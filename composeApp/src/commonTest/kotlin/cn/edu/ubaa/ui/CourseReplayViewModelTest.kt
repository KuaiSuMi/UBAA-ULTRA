package cn.edu.ubaa.ui

import cn.edu.ubaa.api.feature.*
import cn.edu.ubaa.ui.screens.schoolcourse.CourseReplayViewModel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CourseReplayViewModelTest {
  @AfterTest
  fun cleanup() {
    Dispatchers.resetMain()
  }

  @Test
  fun courseAndPlaybackErrorsCanRetryAndBackPreservesSearch() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val course = ReplayCourse("1", "编译技术", "教师", "学院", "学期")
    val lesson = CourseLesson("2", "第一课", "教师", "F102", 1, 2, true)
    var deny = true
    val service =
        object : CourseReplayService() {
          override suspend fun terms(owner: String) = Result.success(emptyList<ReplayTerm>())

          override suspend fun search(owner: String, keyword: String, term: String, page: Int) =
              Result.success(ReplayCoursePage(listOf(course), 1, page))

          override suspend fun lessons(owner: String, courseId: String) =
              Result.success(listOf(lesson))

          override suspend fun videos(owner: String, courseId: String, lessonId: String) =
              if (deny) Result.failure(IllegalStateException("没有观看权限"))
              else
                  Result.success(
                      listOf(ReplayVideo("教师", "https://resource.msa.buaa.edu.cn/test.mp4"))
                  )
        }
    val model = CourseReplayViewModel("owner", service = service)
    model.search("编译技术")
    model.openCourse(course)
    model.play(lesson)
    assertEquals("没有观看权限", model.state.value.error)
    assertNull(model.state.value.videos)
    deny = false
    model.retry()
    assertEquals(1, model.state.value.videos?.size)
    model.back()
    assertNull(model.state.value.lesson)
    assertEquals(lesson, model.state.value.lessons?.single())
    model.back()
    assertEquals("编译技术", model.state.value.keyword)
    assertEquals(course, model.state.value.result?.courses?.single())
    assertFalse(model.state.value.loading)
  }

  @Test
  fun leavingPendingLessonLoadDoesNotReopenTheCourse() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val service =
        object : CourseReplayService() {
          override suspend fun lessons(
              owner: String,
              courseId: String,
          ): Result<List<CourseLesson>> {
            awaitCancellation()
          }
        }
    val model = CourseReplayViewModel("owner", service = service)
    model.openCourse(ReplayCourse("1", "测试", "", "", ""))
    assertTrue(model.state.value.loading)
    model.back()
    advanceUntilIdle()
    assertNull(model.state.value.course)
    assertFalse(model.state.value.loading)
  }
}
