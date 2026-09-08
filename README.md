# Vialen

**轻量、清晰的 Android 代理客户端。**

[下载最新版本](https://github.com/killertop/Vialen/releases/latest) · [版本记录](https://github.com/killertop/Vialen/releases) · [English](#english)

## 中文

Vialen 提供节点与订阅管理、分组、路由和 VPN 连接，采用简洁的黑色主题与独立夜间模式设置。当前正式安装包适用于 Android ARM64（`arm64-v8a`），应用包名为 `com.vialen.app`。

### 功能

- 导入和管理节点、订阅与分组。
- 配置路由，在应用内查看连接状态与流量统计。
- 首次添加或导入节点后，在没有有效选择且 VPN 已停止时自动选中首个节点。
- 启动时不申请通知权限，运行时保留最小 VPN 前台服务通知。
- 统一的 Vialen 图标与关于页，减少无关入口。

### Rust 与订阅处理

Vialen 在订阅处理流程中集成 Rust，并根据实际测量采用混合解析：符合保守检查条件的普通 JSON 使用 Kotlin 快速路径，其余输入继续通过 Rust 处理。该设计兼顾兼容性、可维护性与完整更新流程的效率，不将局部解析速度等同于整体性能或续航提升。

订阅导入提取节点配置，订阅中的分流规则不会自动导入为应用路由规则。

### 安装

从 [Releases](https://github.com/killertop/Vialen/releases) 下载正式 APK，可使用同一版本的 `SHA256SUMS` 文件核验完整性。同签名版本支持覆盖升级。当前版本不提供备份恢复功能。

### 开发与构建

应用层使用 Kotlin，原生组件包含 Rust。构建需要 Android SDK/NDK、Rust 工具链及已准备好的原生依赖。发布构建还需配置签名。

```sh
./gradlew :app:assembleRelease
```

每次构建生成一个 ARM64 APK。仓库提供手动触发的构建工作流，用于从指定源码生成并校验原生组件和未签名 APK；正式签名与发布单独完成。

## English

**A clean, lightweight proxy client for Android.**

[Download the latest release](https://github.com/killertop/Vialen/releases/latest) · [Release history](https://github.com/killertop/Vialen/releases)

Vialen provides profile, subscription, group, routing, and VPN management with a restrained black theme and separate night-mode settings. Current release packages target Android ARM64 (`arm64-v8a`) and use the application ID `com.vialen.app`.

### Features

- Import and manage profiles, subscriptions, and groups.
- Configure routing and view connection status and traffic statistics in the app.
- Automatically select the first profile after an addition or import when there is no valid selection and the VPN is stopped.
- No notification permission request at startup; a minimal VPN foreground-service notification remains while running.
- Consistent Vialen branding and a focused About page.

### Rust and subscription processing

Vialen integrates Rust into its subscription processing pipeline. Its hybrid parser follows measured results: ordinary JSON that passes conservative checks uses a Kotlin fast path, while other inputs continue through Rust. The design balances compatibility, maintainability, and end-to-end update efficiency. Parser-only measurements are not presented as overall performance or battery-life improvements.

Subscription imports extract profile configurations. Routing rules contained in a subscription are not automatically imported as application routing rules.

### Installation

Download the signed APK from [Releases](https://github.com/killertop/Vialen/releases). Use the matching `SHA256SUMS` file to verify its integrity. Builds signed with the same certificate support in-place upgrades. Backup and restore are not available in the current version.

### Development and building

The application layer uses Kotlin, with native components including Rust. Building requires the Android SDK/NDK, the Rust toolchain, and prepared native dependencies. Release signing must be configured separately.

```sh
./gradlew :app:assembleRelease
```

Each build produces one ARM64 APK. A manually triggered repository workflow builds and verifies native components and an unsigned APK from a specified source revision. Official signing and publishing are handled separately.

## 许可证 / License

[LICENSE](LICENSE)
