# Vialen 新客户端：Kotlin + Go

状态：新架构已实现，本地构建与合同测试通过；真机断开，本轮设备验收未完成。采用全新数据库，不提供旧客户端数据迁移。

## 架构决定

选择 Kotlin 管 Android 与应用状态，Go 管协议、配置和 sing-box。保留官方 gomobile 生成的 Java/JNI、Android Java 工具链、AIDL 与 ViewBinding；不维护自制 JNI 框架。新业务核心无 Rust 依赖。

减少的是独立业务实现和必须维护的工具链。少量既有 Java 表单/Android 辅助类继续复用；它们不定义新的协议存储格式或生成运行配置。无需为了文件扩展名再重写成熟基础设施。

```mermaid
flowchart TB
  UI[Kotlin 界面与表单] --> APP[Kotlin 应用服务与状态]
  APP --> DB[Room：Profile JSON、身份、事务]
  APP --> BRIDGE[官方 gomobile：批量 JSON 接口]
  BRIDGE --> IMPORT[Go profile / importer]
  BRIDGE --> COMPILE[Go compiler]
  COMPILE --> CORE[libcore / sing-box：一个 Go 运行时]
  CORE <--> ANDROID[Kotlin VpnService、网络与权限]
```

| 职责 | 唯一负责人 | 边界 |
| --- | --- | --- |
| 节点 URI、订阅格式、协议字段校验 | Go `core/profile`、`core/importer` | 无数据库、Android 或网络 I/O |
| DNS、路由、链、selector、核心配置 | Go `core/compiler` | 接收冻结的应用意图，输出配置与引用元数据 |
| 批量导入、校验、编译、导出 | Go `core/api` | 严格 JSON、输入上限、明确错误 |
| 节点身份、刷新匹配、去重、排序、事务 | Kotlin | 用完整 Profile 语义去重，不能只按服务器地址合并 |
| 界面、权限、VPN 服务、网络变化与调度 | Kotlin | Android 生命周期由 Android 层负责 |
| 连接、DNS 执行、流量与协议运行 | `libcore` / sing-box | 数据包留在 Go/TUN 路径，业务桥接不逐包搬运 |

## 数据与接口

节点保存为 `ProfileDocument(kind=node, profile=Profile)`，链保存有序引用，完整配置和原始出站分别使用独立文档。Room 使用新的数据库名和 version 1；不读取、删除或迁移旧文件。

Java Bean 只作现有表单的临时投影。保存时将编辑差异合并回原始 Profile，保留表单没有表达的请求头、TLS、WireGuard 参数等。Room 真正持久化 `document` 列；重读和编辑后重读都有独立测试。

业务桥接只有四类操作：

1. `import`：整个文档 → Profile 列表与逐条诊断。
2. `validate`：整批 Profile → 校验成功或错误，不逐节点调用 JNI。
3. `compile`：Profile、链、规则、DNS、平台能力 → sing-box 配置及 tag 映射。
4. `export`：Profile → 标准 URI；不能无损表达时明确失败，用户可分享完整 Profile JSON。

订阅部分解析错误或空结果不能替换已保存的组。有意义的省略警告对手动操作可见。来源标识 `sourceKey` 与本地 Profile ID/Room ID 分离；刷新中的重复来源先按完整语义匹配，再按明确顺序匹配。数据库事务结束后通知 UI。

导入文本最多 16 MiB、节点最多 10,000；ZIP 同时限制总解压预算与条目数。JSON 传输另有 20 MiB 编码后上限。取消订阅任务会取消原生请求并等待其退出，取消后不会进入数据库提交。

## 新客户端的功能取舍

- 支持当前常用 SOCKS、HTTP、Shadowsocks、VMess、VLESS、Trojan、Hysteria 2、TUIC、WireGuard、AnyTLS、ShadowTLS。
- 保留当前核心支持的 ECH、UoT、multiplex、Reality、HY2 调优等表单能力；不把“没有旧版本兼容”解释为丢掉现行协议能力。
- 不保留 Universal/Kryo 节点分享、旧 canonical wire、VCW1、旧 Rust 差分/排名接口、旧解析器容错怪癖。
- Hysteria 表单只提供 Hysteria 2。流量嗅探提供关闭和路由判断；已过时的目标覆盖方式退出界面。
- 结构化节点不再叠加任意 JSON 覆盖。需要完整控制时使用独立原始出站或完整配置模式，避免多处配置互相覆盖。
- 原始出站由 typed sing-box option 解码并纳入链/selector；生成器拥有 tag、detour 等引用，禁止用户覆盖它们。

## 工具链

固定 Go 1.27.1、sing-box 1.14.0 及仓库既有补丁、官方 x/mobile `v0.0.0-20260908204917-8b95e45f8d3e`、JDK 25、NDK 28.1.13356709。官方 gomobile 已实际构建 Android arm64 AAR。原先 gomobile 分支在 Go 1.27 下存在构建兼容问题，因此改用已验证的官方工具，减少私有分支维护。

主机合同测试调用同一个 Go API 的本地执行程序；Android 调用同一模块编入的 AAR。主机通过不代表 Android native、R8、设备或 VPN 已通过。

## 验证与性能判断

独立验证包括：Go 单测/race/fuzz；Android 模型与实际 Room 往返；真实固定版本 sing-box 构造/关闭；selector 实际切换；原生 HTTP 取消与大小上限；Android 导入/订阅/生命周期测试；Debug 与 Release/R8 构建；APK 原生库、16 KiB 对齐、源码与产物来源检查。

性能收益的合理预期来自移除第二个原生业务库、重复解析/转换和细碎 JNI 调用。不能据此承诺更低内存或更高吞吐。Go 有 GC，JSON 传输仍有分配和复制；包体、PSS、批量导入耗时、启动与真实网络表现必须实测。

`NewClientCoreNativeTest` 记录 1000 节点导入、保存/重读、编译阶段耗时及 PSS/ART 分配量；它不把主机微基准当作手机端速度，也不在未做可比实验时声称比 Rust 更快。

本地版本为 1.7.4，源码 VERSION_CODE=62（ARM64 APK versionCode=310）。207 项 JVM 测试零失败、零跳过；Go business race、完整 libcore 测试、Debug/AndroidTest/Release 构建及 Release lint 通过。APK 无 Rust 库，AAR/APK 的 ARM64 与 ELF 16 KiB 对齐检查通过。真机安装、UI、VPN、手机端性能尚未验证；Release APK 为未签名候选，不作为已验收发行版。所有删除的旧实现和专用兼容测试均移入系统垃圾篓，可恢复；原工作树与旧数据库保留。
