# Vialen for Android

Vialen 是基于 NekoBox for Android 的 Android 代理客户端，使用 sing-box 内核，并包含本项目的 Rust 组件。

安装包标识为 `com.vialen.app`，Debug 构建为 `com.vialen.app.debug`。当前分支仅构建和支持 `arm64-v8a`（Android ARM64），APK、AAB、Go 内核和 Rust JNI 均使用这一架构。

## 构建与发布

应用仅保留英文和中文（简体、繁体），发布构建不再区分 OSS、F-Droid、Play 或 Preview 渠道。在 Android SDK/NDK、原生依赖及签名配置就绪后执行：

```sh
./gradlew :app:assembleRelease
```

每次发布构建仅生成一个 ARM64 APK，位于 `app/build/outputs/apk/release/`，文件名为 `Vialen-<version>-arm64-v8a.apk`；未配置发布签名时文件名含 `-unsigned`。开发及测试仍使用 `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest` 和 `:app:connectedDebugAndroidTest`。

本仓库的本地构建和验证结果以对应的构建日志及验收记录为准。应用内发布入口已配置为 [Vialen Releases](https://github.com/killertop/Vialen/releases)，当前版本尚未发布。尚未配置独立的官方下载站点或社区入口；上游 NekoBox 的 Release 不作为 Vialen 更新来源。

## 订阅与兼容性

支持范围以当前实现与测试为准。订阅解析提取节点出站，订阅中的分流规则不自动作为应用规则导入。内部源码包名、JNI 导出符号与插件接口保留既有兼容标识；它们与 Android 安装包标识分别管理。

## 上游与许可

Vialen 基于 [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 开发。保留仓库的 LICENSE、源码版权声明及第三方许可；上游链接用于来源追溯，不代表 Vialen 官方下载或服务入口。

- 内核：[SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- Android GUI 来源：[SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)、[shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- Web Dashboard 来源：[Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
- 上游插件参考：[NekoBox 插件文档](https://matsuridayo.github.io/nb4a-plugin/)
