# 教室直播（Android）

在主页今日课程或课表课程详情点击“观看直播”，按上课地点匹配教室。没有唯一匹配时，进入教室搜索。

“高级功能 → 教室直播”和“空教室查询 → 教室直播”可浏览全部教室，按校区筛选、按教室名称搜索。

播放页支持教师、学生、屏幕视角切换、暂停、重试和横屏全屏。退出播放页或切到后台会释放播放器；返回前台重新连接直播。

## 教室目录

目录提取自用户提供的《教室直播.html》，内置于 APK，不执行原 HTML 的脚本。

| 校区 | 教室数 |
| --- | ---: |
| 学院路 | 168 |
| 沙河 | 155 |
| 杭州国际创新研究院 | 58 |

共 381 间教室，其中 373 间提供直播地址，总计 1109 个视角地址。缺少地址的视角不显示切换按钮；没有地址的教室显示“暂无直播地址”。直播画面由校方视频源提供，杭州地址通过校内网络连接。

## 实现

- `androidApp/src/main/assets/live/classrooms.json`：教室与视角地址。
- `LiveClassroomCatalog`：目录解析、校区筛选、课程地点匹配；统一全角字符、教室括号、空格与连字符。
- `LocalClassroomLiveLauncher`：主页、课程详情、高级功能和教室查询共用 Android 入口。
- `ClassroomLiveActivity`：教室选择、播放状态和全屏。
- 使用 [AndroidX Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer/hello-world) 及其 [HLS 模块](https://developer.android.com/media/media3/exoplayer/hls) 播放直播。

## 验证

`LiveClassroomCatalogTest` 检查完整目录、课程地点匹配、校区搜索和缺失视角；`ClassroomLiveEntryTest` 与 `HomeAttendanceTest` 检查课程和教室入口。

连接 Android 设备或模拟器，并接入可访问直播源的网络后，可运行真实播放测试：

```bash
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveCampusSmoke=true -Pandroid.testInstrumentationRunnerArguments.class=cn.edu.ubaa.live.ClassroomLivePlaybackTest
```

该测试检查三个视角实际解码输出视频帧、全屏、后台释放及返回前台重新播放；默认测试任务跳过校园网络实测。
