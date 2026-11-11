package cn.edu.ubaa.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionVerificationDialog(
    state: AuthUiState,
    form: LoginFormState,
    onPasswordChange: (String) -> Unit,
    onCaptchaChange: (String) -> Unit,
    onRefreshCaptcha: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("验证统一认证") },
      text = {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text("账号 ${form.username}")
          OutlinedTextField(
              value = form.password,
              onValueChange = onPasswordChange,
              label = { Text("密码") },
              visualTransformation = PasswordVisualTransformation(),
              singleLine = true,
              enabled = !state.isLoading,
              modifier = Modifier.fillMaxWidth(),
          )
          if (state.captchaRequired) {
            state.captchaInfo?.let {
              CaptchaImage(
                  it,
                  onRefreshCaptcha,
                  state.isLoading,
                  Modifier.fillMaxWidth().height(64.dp),
              )
            }
            OutlinedTextField(
                value = form.captcha,
                onValueChange = onCaptchaChange,
                label = { Text("验证码") },
                singleLine = true,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
          }
          state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        TextButton(
            onClick = onConfirm,
            enabled =
                !state.isLoading &&
                    form.password.isNotBlank() &&
                    (!state.captchaRequired || form.captcha.isNotBlank()),
        ) {
          Text(if (state.isLoading) "验证中" else "验证")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
  )
}
