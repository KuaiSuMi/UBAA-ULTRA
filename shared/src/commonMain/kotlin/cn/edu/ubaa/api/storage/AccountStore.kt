package cn.edu.ubaa.api.storage

import cn.edu.ubaa.api.ConnectionMode
import cn.edu.ubaa.model.dto.UserData
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedAccount(
    val user: UserData,
    val username: String,
    val password: String?,
    val mode: ConnectionMode,
)

object AccountStore {
  private var backing: Settings? = null
  var settings: Settings
    get() = backing ?: Settings().also { backing = it }
    set(value) {
      backing = value
    }

  private val json = Json { ignoreUnknownKeys = true }
  private val revision = MutableStateFlow(0L)
  val changes = revision.asStateFlow()

  fun accounts(): List<SavedAccount> =
      settings.getStringOrNull("account_ids")?.split(',').orEmpty().mapNotNull(::get)

  fun get(id: String): SavedAccount? =
      settings.getStringOrNull("account_$id")?.let {
        runCatching { json.decodeFromString<SavedAccount>(it) }.getOrNull()
      }

  fun active(): SavedAccount? = settings.getStringOrNull("active_account")?.let(::get)

  fun save(account: SavedAccount) {
    val id = account.user.schoolid
    require(Regex("[A-Za-z0-9_-]{1,40}").matches(id)) { "学号格式无效" }
    val ids = (accounts().map { it.user.schoolid } + id).distinct()
    settings.putString("account_$id", json.encodeToString(account))
    settings.putString("account_ids", ids.joinToString(","))
    settings.putString("active_account", id)
    revision.update { it + 1 }
  }

  fun deactivate() {
    settings.remove("active_account")
    revision.update { it + 1 }
  }

  fun remove(id: String) {
    settings.putString(
        "account_ids",
        accounts().filterNot { it.user.schoolid == id }.joinToString(",") { it.user.schoolid },
    )
    settings.remove("account_$id")
    if (settings.getStringOrNull("active_account") == id) deactivate()
    revision.update { it + 1 }
  }
}
