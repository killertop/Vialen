# Vialen

**轻量、清晰的 Android 代理客户端。**

[下载最新版本](https://github.com/killertop/Vialen/releases/latest) · [版本记录](https://github.com/killertop/Vialen/releases) · [English](#english)

## 中文

Vialen 提供节点与订阅管理、分组、路由和 VPN 连接，统一采用浅色界面，不随系统外观或省电模式切换主题。当前正式安装包适用于 Android ARM64（`arm64-v8a`），应用包名为 `com.vialen.app`。

### 功能

- 导入和管理节点、订阅与分组。
- 配置路由，在应用内查看连接状态与流量统计。
- 首次添加或导入节点后，在没有有效选择且 VPN 已停止时自动选中首个节点。
- 启动时不申请通知权限，运行时保留最小 VPN 前台服务通知。
- 统一的 Vialen 图标与关于页，减少无关入口。

### 订阅处理

协议 URI、订阅格式和配置生成统一由 Go 核心处理，输出完整 Profile 数据。Kotlin 负责节点身份、语义去重、刷新匹配和数据库事务。所有格式走同一条验证路径；部分解析错误或空结果不会替换已有订阅。架构与验证边界见 [新客户端设计](docs/go-core-migration.md)。

订阅导入提取节点配置，订阅中的分流规则不会自动导入为应用路由规则。

### 安装

从 [Releases](https://github.com/killertop/Vialen/releases) 下载正式 APK，可使用同一版本的 `SHA256SUMS` 文件核验完整性。此开发分支采用全新的数据库，不迁移旧客户端数据；旧数据文件保留。当前版本不提供旧备份恢复。

### 开发与构建

应用层使用 Kotlin，Go 业务核心与 sing-box 通过官方 gomobile 绑定集成到同一个 libcore AAR。构建使用 Go 1.27.1、JDK 25（CI 固定 25.0.2）、Android SDK/NDK 及已准备好的原生依赖，不需要 Rust 工具链。将 `JAVA_HOME` 指向 JDK 25；Gradle、编译器与 JVM 测试直接运行在该 JDK 上。发布构建还需配置签名。

当前源码构建最低支持 Android 7.0（API 24），编译使用 SDK 37（SDK 包 `platforms;android-37.0`）及 Build Tools 36.0.0，目标版本保持 API 35。依赖版本集中在 `gradle/libs.versions.toml`。

```sh
bash libcore/init.sh
bash libcore/build.sh
go -C core test -mod=readonly ./...
go -C core test -mod=readonly -race ./...
./gradlew :app:assembleRelease
```

Go 测试独立于 Android 和原生 AAR 构建；CI 对普通测试与竞态检测分别执行检查。Android 安装、界面及 VPN 验收仅使用已连接真机，CI 构建与 Go 测试不替代真机验收。

每次构建生成一个 ARM64 APK。仓库提供手动触发的构建工作流，用于从指定源码生成并校验原生组件和未签名 APK；正式签名与发布单独完成。

## English

**A clean, lightweight proxy client for Android.**

[Download the latest release](https://github.com/killertop/Vialen/releases/latest) · [Release history](https://github.com/killertop/Vialen/releases)

Vialen provides profile, subscription, group, routing, and VPN management with one light appearance that does not follow system appearance or battery saver. Current release packages target Android ARM64 (`arm64-v8a`) and use the application ID `com.vialen.app`.

### Features

- Import and manage profiles, subscriptions, and groups.
- Configure routing and view connection status and traffic statistics in the app.
- Automatically select the first profile after an addition or import when there is no valid selection and the VPN is stopped.
- No notification permission request at startup; a minimal VPN foreground-service notification remains while running.
- Consistent Vialen branding and a focused About page.

### Subscription processing

The Go core owns URI and subscription parsing, typed Profile validation, and configuration compilation. Kotlin owns profile identity, semantic deduplication, refresh matching, and database transactions. Partial or empty imports cannot replace a saved subscription. See the [new client architecture](docs/go-core-migration.md).

Subscription imports extract profile configurations. Routing rules contained in a subscription are not automatically imported as application routing rules.

### Installation

Download the signed APK from [Releases](https://github.com/killertop/Vialen/releases). Use the matching `SHA256SUMS` file to verify its integrity. This development branch uses new databases without migrating old-client data. Old data files remain intact; old backup restoration is not supported.

### Development and building

The application layer uses Kotlin. The Go business core and sing-box share one libcore AAR through official gomobile bindings. Builds require Go 1.27.1, JDK 25 (CI pins 25.0.2), the Android SDK/NDK, and prepared native dependencies; a Rust toolchain is not required. Set `JAVA_HOME` to JDK 25; Gradle, compilers, and JVM tests run directly on that JDK. Release signing must be configured separately.

Builds from the current source require Android 7.0 (API 24) or later. Compilation uses SDK 37 (`platforms;android-37.0`) and Build Tools 36.0.0, while the target remains API 35. Dependency versions are centralized in `gradle/libs.versions.toml`.

```sh
bash libcore/init.sh
bash libcore/build.sh
go -C core test -mod=readonly ./...
go -C core test -mod=readonly -race ./...
./gradlew :app:assembleRelease
```

Go tests run independently of Android and native AAR builds; CI checks both regular tests and the race detector. Android installation, UI, and VPN acceptance use connected physical devices only. CI builds and Go tests do not replace device acceptance.

Each build produces one ARM64 APK. A manually triggered repository workflow builds and verifies native components and an unsigned APK from a specified source revision. Official signing and publishing are handled separately.

## 许可证 / License

[LICENSE](LICENSE)
