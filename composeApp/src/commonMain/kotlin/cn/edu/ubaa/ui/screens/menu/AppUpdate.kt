package cn.edu.ubaa.ui.screens.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cn.edu.ubaa.api.auth.AndroidRelease

val LocalUpdateInstaller =
    staticCompositionLocalOf<(@Composable (AndroidRelease, () -> Unit) -> Unit)?> { null }
val LocalCheckForUpdates = staticCompositionLocalOf<(() -> Unit)?> { null }
