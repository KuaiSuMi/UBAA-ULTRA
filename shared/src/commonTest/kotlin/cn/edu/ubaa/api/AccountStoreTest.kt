package cn.edu.ubaa.api

import cn.edu.ubaa.api.storage.AccountStore
import cn.edu.ubaa.api.storage.SavedAccount
import cn.edu.ubaa.model.dto.UserData
import com.russhwolf.settings.MapSettings
import kotlin.test.*

class AccountStoreTest {
  @BeforeTest
  fun setup() {
    AccountStore.settings = MapSettings()
  }

  @Test
  fun savesAndRemovesOnlySelectedAccount() {
    val one = SavedAccount(UserData("甲", "111"), "111", "password-a", ConnectionMode.DIRECT)
    val two = SavedAccount(UserData("乙", "222"), "222", "password-b", ConnectionMode.WEBVPN)
    AccountStore.save(one)
    AccountStore.save(two)
    assertEquals(two, AccountStore.active())
    AccountStore.remove("111")
    assertEquals(listOf(two), AccountStore.accounts())
    AccountStore.deactivate()
    assertNull(AccountStore.active())
    assertEquals(two, AccountStore.get("222"))
  }

  @Test
  fun doesNotKeepPasswordWhenRememberIsDisabled() {
    val one = SavedAccount(UserData("甲", "111"), "111", "password-a", ConnectionMode.DIRECT)
    AccountStore.save(one)
    AccountStore.save(one.copy(password = null))
    assertNull(AccountStore.get("111")?.password)
  }
}
