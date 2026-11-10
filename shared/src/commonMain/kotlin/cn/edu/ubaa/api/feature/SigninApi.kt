package cn.edu.ubaa.api.feature

import cn.edu.ubaa.api.ConnectionRuntime
import cn.edu.ubaa.api.auth.ApiCallException
import cn.edu.ubaa.api.auth.ApiClientProvider
import cn.edu.ubaa.api.auth.safeApiCall
import cn.edu.ubaa.api.core.ApiClient
import cn.edu.ubaa.attendance.beijingDate
import cn.edu.ubaa.model.dto.*
import io.ktor.client.request.*

interface SigninApiBackend {
  suspend fun getClasses(date: String): Result<SigninStatusResponse>

  suspend fun performSignin(courseId: String): Result<SigninActionResponse>
}

/** 课堂签到 API 服务。 用于查询今日可签到的课堂及执行签到动作。 */
class SigninApi(
    private val backendProvider: () -> SigninApiBackend = {
      ConnectionRuntime.apiFactory().signinApi()
    }
) {
  internal constructor(backend: SigninApiBackend) : this({ backend })

  constructor(apiClient: ApiClient) : this({ RelaySigninApiBackend(apiClient) })

  private fun currentBackend(): SigninApiBackend = backendProvider()

  /**
   * 按日期获取课堂签到状态。
   *
   * @return 签到状态响应，包含课堂列表。
   */
  suspend fun getClasses(date: String = beijingDate()): Result<SigninStatusResponse> {
    return currentBackend().getClasses(date)
  }

  /**
   * 执行课堂签到。
   *
   * @param courseId 课程 ID。
   * @return 签到操作执行结果。
   */
  suspend fun performSignin(courseId: String): Result<SigninActionResponse> {
    return currentBackend().performSignin(courseId)
  }
}

internal class RelaySigninApiBackend(private val apiClient: ApiClient = ApiClientProvider.shared) :
    SigninApiBackend {
  override suspend fun getClasses(date: String): Result<SigninStatusResponse> {
    if (date != beijingDate()) return Result.failure(ApiCallException("历史签到请使用直连或 WebVPN 模式"))
    return safeApiCall { apiClient.getClient().get("api/v1/signin/today") }
  }

  override suspend fun performSignin(courseId: String): Result<SigninActionResponse> {
    return safeApiCall {
      apiClient.getClient().post("api/v1/signin/do") { parameter("courseId", courseId) }
    }
  }
}
