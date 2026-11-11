# 应用内更新（Android）

应用启动时自动检查新版本，也可通过「设置 → 检查更新」手动查询。更新来源为 [UBAA-ULTRA Releases](https://github.com/KuaiSuMi/UBAA-ULTRA/releases)，无需连接校园网或中转服务器。

发现新版本后显示版本号和发布说明，点击「下载并安装」可在应用内下载 APK，并查看进度或取消下载。下载完成后调用 Android 系统安装器；首次安装更新时，按系统页面允许 UBAA 安装应用。

下载过程核对文件大小及 GitHub 资产的 SHA-256，安装前检查包名、版本代码和签名。更新包须与已安装版本使用同一签名，以便保留账号、设置及数据。更新操作不新增常驻通知。

## 发布约定

- 标签为 `v主版本.次版本.修订版本`，同时递增 `gradle.properties` 中的版本名称与版本代码。
- Android 资产名称为 `UBAA-Android-v版本.apk`。
- 上传完整资产后发布 Release；应用只检查正式版本，不安装草稿或预发布版本。
- 当前安装包沿用 1.9.0 本地 APK 的调试签名。

接口依据：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases)。
