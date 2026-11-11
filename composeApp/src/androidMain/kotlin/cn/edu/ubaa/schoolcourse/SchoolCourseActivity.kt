package cn.edu.ubaa.schoolcourse

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.ui.screens.schoolcourse.*
import cn.edu.ubaa.ui.theme.UBAATheme

class SchoolCourseActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val owner =
        AccountStore.active()?.user?.schoolid
            ?: run {
              finish()
              return
            }
    enableEdgeToEdge()
    setContent {
      UBAATheme {
        Surface {
          val model = viewModel { SchoolCourseViewModel(owner) }
          val state by model.state.collectAsState()
          LaunchedEffect(model) { if (state.result == null && !state.loading) model.search() }
          CompositionLocalProvider(
              LocalCourseReplayLauncher provides
                  { course ->
                    startActivity(
                        Intent(this, CourseReplayActivity::class.java).putExtra("course", course)
                    )
                  }
          ) {
            SchoolCourseScreen(state, model::search, ::finish)
          }
        }
      }
    }
  }
}
