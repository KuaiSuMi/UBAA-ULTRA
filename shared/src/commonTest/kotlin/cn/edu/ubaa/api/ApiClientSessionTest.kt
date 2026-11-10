package cn.edu.ubaa.api

import cn.edu.ubaa.api.core.ApiClient
import cn.edu.ubaa.api.storage.AuthTokensStore
import cn.edu.ubaa.api.storage.StoredAuthTokens
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ApiClientSessionTest {
  @BeforeTest
  fun setup() {
    AuthTokensStore.settings = MapSettings()
    ConnectionModeStore.settings = MapSettings()
  }

  @Test
  fun replacingTokensUpdatesExistingBearerProvider() = runTest {
    val authorizations = mutableListOf<String?>()
    val engine = MockEngine { request ->
      authorizations += request.headers[HttpHeaders.Authorization]
      respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val api = ApiClient(engine)
    try {
      api.updateTokens(StoredAuthTokens("first", "refresh-one"))
      api.getClient().get("api/v1/user/info")
      api.updateTokens(StoredAuthTokens("second", "refresh-two"))
      api.getClient().get("api/v1/user/info")
      api.clearAuthTokens()
      api.getClient().get("api/v1/user/info")
      assertEquals(listOf("Bearer first", "Bearer second", null), authorizations)
    } finally {
      api.close()
    }
  }

  @Test
  fun serviceFailureDuringRefreshDoesNotDeletePersistedTokens() = runTest {
    val engine = MockEngine { request ->
      if (request.url.encodedPath.endsWith("/refresh"))
          respond("{}", HttpStatusCode.ServiceUnavailable)
      else
          respond(
              "{}",
              HttpStatusCode.Unauthorized,
              headersOf(HttpHeaders.WWWAuthenticate, "Bearer"),
          )
    }
    val api = ApiClient(engine)
    try {
      api.updateTokens(StoredAuthTokens("first", "refresh-one"))
      assertFailsWith<cn.edu.ubaa.api.auth.ApiCallException> {
        api.getClient().get("api/v1/auth/status")
      }
      assertEquals("refresh-one", AuthTokensStore.get()?.refreshToken)
    } finally {
      api.close()
    }
  }
}
