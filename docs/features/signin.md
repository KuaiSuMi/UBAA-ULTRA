# 课程签到

课程签到模块查询当天可签到课程，并提交签到动作。它依赖上游课堂签到状态，属于写操作功能，必须保留服务端和本地实现的一致错误语义。

## 用户能力

- 查看当天签到课程和当前签到状态。
- 对可签到课程提交签到。
- 刷新当天签到状态。

## Android 课前自动签到

主页直接提供「自动签到」开关。允许通知和定时执行后开启，应用同步当前学期课表，并保存当前账号的设置。调课后重新本地化课表即可更新安排。

上课前 10 分钟发送上课通知；签到时间为课前 5 分钟加上前后最多 60 秒的随机偏移。每门课的执行时间会保存，重新打开应用保持原安排。签到成功后发送通知，失败原因显示在课程记录中。任务未开放时在课前重试，上课后停止提交。通知只有上课、打卡成功两类。

主页的今日课程和周课表的课程卡片直接显示「已签到」「待签到」「签到失败」或「暂无记录」。状态按账号、课程日期、名称和开始时间对应，后台签到完成后自动更新。切换课表周次会按日期读取这一周的校方签到记录，刷新可重新同步，安装前完成的签到也会显示。

Android 使用系统定时唤醒启动课前签到，由 WorkManager 执行请求和重试，并保存上课通知任务。关闭界面、划掉最近任务或重启手机后继续调度。任务执行时恢复保存的登录会话，需要时使用已保存密码重新登录。系统强行停止应用后需要重新打开；后台执行时间受系统省电调度影响。

## 技术路径

- `SigninViewModel` 管理当天签到、按周历史查询和提交结果。
- `SigninApiBackend` 提供按日期查询和提交签到两个动作。
- `AttendanceScheduler` 为下一次签到注册精确唤醒，触发后启动 WorkManager 并安排下一节课。通知任务按账号和课程唯一标识去重；开机、应用更新、时间变化和定时权限授予后重新同步安排。
- `LocalSigninApiBackend` 在本地连接模式下复用本地 SSO Cookie，先访问 iclass MyCenter 跳转页提取 `loginName`，再访问上游签到接口。
- `SigninService` 在服务端维护上游客户端上下文，服务端实现同样先通过已登录 SSO 会话获取 `loginName`，应用停止和定时清理时释放缓存。

## 接口

- `GET /api/v1/signin/today`
- `POST /api/v1/signin/do`

## 来源文件

- `composeApp/src/commonMain/kotlin/cn/edu/ubaa/ui/screens/signin/SigninScreen.kt`
- `composeApp/src/commonMain/kotlin/cn/edu/ubaa/ui/screens/signin/SigninViewModel.kt`
- `shared/src/commonMain/kotlin/cn/edu/ubaa/api/feature/SigninApi.kt`
- `shared/src/commonMain/kotlin/cn/edu/ubaa/api/SigninLoginNameSupport.kt`
- `shared/src/commonMain/kotlin/cn/edu/ubaa/api/local/LocalSigninApi.kt`
- `shared/src/commonMain/kotlin/cn/edu/ubaa/model/dto/Signin.kt`
- `server/src/main/kotlin/cn/edu/ubaa/signin/SigninRoutes.kt`
- `server/src/main/kotlin/cn/edu/ubaa/signin/SigninService.kt`
- `server/src/main/kotlin/cn/edu/ubaa/signin/SigninClient.kt`
- `shared/src/commonTest/kotlin/cn/edu/ubaa/api/SigninLoginNameSupportTest.kt`
- `shared/src/commonTest/kotlin/cn/edu/ubaa/api/LocalSigninApiBackendTest.kt`
- `shared/src/jvmTest/kotlin/cn/edu/ubaa/api/LocalSigninRealIntegrationTest.kt`
- `server/src/test/kotlin/cn/edu/ubaa/signin/SigninClientTest.kt`
- `server/src/test/kotlin/cn/edu/ubaa/utils/VpnCipherTest.kt`
