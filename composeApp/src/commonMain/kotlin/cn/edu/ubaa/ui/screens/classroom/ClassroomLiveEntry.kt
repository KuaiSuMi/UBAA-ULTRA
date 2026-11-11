package cn.edu.ubaa.ui.screens.classroom

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalClassroomLiveLauncher =
    staticCompositionLocalOf<((place: String?, course: String?) -> Unit)?> { null }

@Composable
fun CourseLiveButton(place: String?, course: String) {
  val launch = LocalClassroomLiveLauncher.current ?: return
  TextButton(onClick = { launch(place, course) }) { Text("观看直播") }
}
