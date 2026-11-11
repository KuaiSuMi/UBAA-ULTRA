package cn.edu.ubaa.api

import cn.edu.ubaa.api.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class UpdateServiceTest {
  private fun release(
      version: String = "1.10.0",
      url: String =
          "https://github.com/KuaiSuMi/UBAA-ULTRA/releases/download/v$version/UBAA-Android-v$version.apk",
      digest: String? = "sha256:" + "a".repeat(64),
  ) =
      GithubRelease(
          "v$version",
          "新增健康云",
          assets = listOf(GithubAsset("UBAA-Android-v$version.apk", url, 1000, digest)),
      )

  private fun service(release: GithubRelease): UpdateService =
      UpdateService(
          HttpClient(
              MockEngine { request ->
                assertEquals(
                    "https://api.github.com/repos/KuaiSuMi/UBAA-ULTRA/releases/latest",
                    request.url.toString(),
                )
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                respond(Json.encodeToString(release), HttpStatusCode.OK)
              }
          )
      )

  @Test
  fun selectsAndroidAssetAndComparesNumericVersions() = runTest {
    val service = service(release())
    try {
      val result = service.checkUpdate("1.9.1").getOrThrow()
      assertEquals("1.10.0", result?.version)
      assertEquals(1000L, result?.bytes)
      assertEquals("a".repeat(64), result?.sha256)
      assertEquals("新增健康云", result?.notes)
    } finally {
      service.close()
    }
  }

  @Test
  fun sameAndOlderVersionsDoNotOfferDowngrade() = runTest {
    val service = service(release("1.9.1"))
    try {
      assertNull(service.checkUpdate("1.9.1").getOrThrow())
      assertNull(service.checkUpdate("1.10.0").getOrThrow())
    } finally {
      service.close()
    }
  }

  @Test
  fun previewAndDraftReleasesAreNotInstalled() = runTest {
    for (release in listOf(release().copy(draft = true), release().copy(prerelease = true))) {
      val service = service(release)
      try {
        assertNull(service.checkUpdate("1.9.1").getOrThrow())
      } finally {
        service.close()
      }
    }
  }

  @Test
  fun missingApkOrIntegrityAndExternalDownloadsAreReported() = runTest {
    for (release in
        listOf(
            release().copy(assets = emptyList()),
            release(digest = null),
            release(digest = "md5:abc"),
            release(url = "https://other.example/update.apk"),
        )) {
      val service = service(release)
      try {
        assertTrue(service.checkUpdate("1.9.1").isFailure)
      } finally {
        service.close()
      }
    }
  }

  @Test
  fun networkFailureIsDifferentFromNoUpdate() = runTest {
    val service =
        UpdateService(
            HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) })
        )
    try {
      assertTrue(service.checkUpdate().isFailure)
    } finally {
      service.close()
    }
  }

  @Test
  fun cancelledCheckStaysCancelled() = runTest {
    val service = UpdateService(HttpClient(MockEngine { throw CancellationException("cancelled") }))
    try {
      assertFailsWith<CancellationException> { service.checkUpdate() }
    } finally {
      service.close()
    }
  }
}
