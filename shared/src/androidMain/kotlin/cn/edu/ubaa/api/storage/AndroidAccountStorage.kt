package cn.edu.ubaa.api.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 凭据使用设备 Keystore 加密，账号列表不进入系统云备份。 */
fun initializeAndroidAccountStorage(context: Context) {
  val settings =
      EncryptedAccountSettings(
          SharedPreferencesSettings(
              context.getSharedPreferences("account_vault", Context.MODE_PRIVATE)
          )
      )
  AccountStore.settings = settings
  CredentialStore.settings = settings
}

private class EncryptedAccountSettings(private val delegate: Settings) : Settings by delegate {
  private val key: SecretKey by lazy {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey("ubaa_accounts", null) as? SecretKey)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
          init(
              KeyGenParameterSpec.Builder(
                      "ubaa_accounts",
                      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                  )
                  .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                  .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                  .build()
          )
          generateKey()
        }
  }

  override fun putString(key: String, value: String) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, this.key)
    delegate.putString(
        key,
        Base64.encodeToString(
            cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        ),
    )
  }

  override fun getStringOrNull(key: String): String? =
      delegate.getStringOrNull(key)?.let { stored ->
        runCatching {
              val bytes = Base64.decode(stored, Base64.NO_WRAP)
              val cipher = Cipher.getInstance("AES/GCM/NoPadding")
              cipher.init(
                  Cipher.DECRYPT_MODE,
                  this.key,
                  GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
              )
              String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
            .getOrElse {
              delegate.remove(key)
              null
            }
      }

  override fun getString(key: String, defaultValue: String): String =
      getStringOrNull(key) ?: defaultValue
}
