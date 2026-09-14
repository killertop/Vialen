# Public test material

Only synthetic fixtures belong in this directory. Machine-specific reports, raw logs,
screenshots and databases are kept outside the repository. Historical reports were
archived locally during the public-content cleanup; their removal does not invalidate
past tests, but those results must not be presented as current verification.

See AGENTS.md for the mandatory release privacy checks.

## 2026-09-14：七项缺陷复核与四项历史修复回归

### 范围与基线

- 开始 HEAD：`7c1ebbf9b5c3425634d17c11cb0b8250f8cc25ca`，`main`，工作树干净、仅一个工作树，无既有未提交改动需要保全。
- 开始及结束源码版本均为 **2.0.8 / VERSION_CODE=96**；未修改版本、工具链、依赖锁定版本、签名或发布权限。
- 旧审查 SHA `1b9d8c00d626d6b7f08f155b31705cb7971ec950` 已在前次隐私历史清理中重写，对应当前历史的 `a96fdc1`。从该源码基线到本次开始 HEAD 的变化是公开内容清理，七项源码问题仍存在。
- 本轮为重点调用链审查和针对性验证，不是全仓逐行审计。未读取生产配置、订阅凭据或签名资料，未安装生产包、切换生产连接、修改手机网络或显示设置。
- 本轮仅本地提交；提交编号见本文件末尾的收尾记录及本地 `git log`，没有 push 或发布。

### 七项结论及修复证据

| 项目 | 结论 | 触发条件、根因与实际修复 | 关键源码位置 |
|---|---|---|---|
| A 订阅竞态 | 确认存在并修复 | 下载期间改配置或新请求先完成，旧实现仅依赖进程内集合并整行回写旧分组。新增 SQLite 配置版本与请求代次，在提交事务中同时检查；读取新分组，仅合并远端元数据。 | `group/SubscriptionRefresh.kt:13,35`、`group/SubscriptionPersistence.kt:39`、`group/GroupUpdater.kt:97` |
| B 连接依赖丢失 | 确认存在并修复 | Clash 的 `dialer-proxy` 未映射、未拒绝。现在非空依赖产生 `DEPENDENT_NODE`，批次完整性检查拒绝整批提交；额外检查无法保留的绑定、路由标记和安全约束。 | `core/importer/clash.go:45`、`core/importer/singbox.go:128` |
| C 规则保存与运行不一致 | 确认存在并修复 | 单端口 0、非法匹配或失效目标可写库后才编译失败。保存及重新启用均走运行编译器同源匹配校验，并检查目标存在、应用 UID、规则文件与自定义内容。 | `database/RouteRuleSet.kt:51`、`database/ProfileManager.kt:259,287`、`core/compiler/rules.go` |
| D 纯代理前台服务 | 确认存在并修复 | Android 14+ 纯代理不必然满足 systemExempted 条件，旧实现吞掉 startForeground 异常。VPN 保留其类型，纯代理使用 specialUse；启动流程等待通知建立，失败进入统一停止清理。 | `bg/ServiceNotification.kt:47,94`、`bg/BaseService.kt:565`、`app/src/main/AndroidManifest.xml:17,233` |
| E 旧 Android IPv6 UID 查询 | 确认存在并修复 | 裸 IPv6 拼接端口后解析失败且错误被忽略。改为 ParseAddr + AddrPortFrom，源和目标均检查错误及 int32 端口范围，保留端口 0 的通配语义。 | `libcore/platform_box.go:128`、`libcore/procfs/address.go` |
| F 外部订阅绕过链接校验 | 确认存在并修复 | 外部导入调用 createGroup 时未统一规范化。新增、修改均在 GroupManager 边界验证；非法输入写库前拒绝，旧 content URI 必须匹配实际记录且有持久读授权。 | `database/GroupManager.kt:87,104`、`group/SubscriptionLink.kt`、`ui/MainActivity.kt:216` |
| G 清空无关分组选中项 | 确认存在并修复 | 旧 clearGroup 无条件先清选择。现在先在节点库事务内判断归属并删除，成功后在配置库做条件更新，用户期间切换的选择不会被清掉。 | `database/GroupManager.kt:56`、`database/DataStore.kt:45` |

上表省略前缀的 Kotlin 源码位于 `app/src/main/java/io/nekohasekai/sagernet/`。

A 使用独立 `subscription_refresh_state` 表，避免旧 ProxyGroup 整行更新回退代次。触发器在首次请求离开事务前安装，覆盖其他连接及直接 DAO 的分组变更；网络和解析在事务外。最新请求失败或取消不会重新放行旧结果；重试获取新代次；删除分组级联清除状态，旧结果不能重建节点。自动入口在事务内再次检查 autoUpdate。两个独立 SQLite 连接及并行协程测试覆盖这些提交语义；本轮没有真实 Android 双进程设备执行结果。

B 的字段策略：缺失或空字符串允许，显式 null、错误类型和纯空白拒绝，非空依赖明确报结构化错误。未知普通扩展字段仍可存在。WireGuard 的私钥继续按既有模型处理，不被当作不支持的 TLS 私钥。依据 [Mihomo dialer-proxy 文档](https://wiki.metacubex.one/en/config/proxies/dialer-proxy/)，依赖指定出站/组会影响真实连接路径，不能静默省略。

C 新增 `validate_rule` 核心接口调用同一个 `compileMatch`，不创建或启动临时实例。Kotlin 的单端口解析继续使用 ConfigSnapshot.match，Go 校验保留合法开放区间。自定义规则 JSON 仍可在表单看到，但保存前明确拒绝，不产生无法运行的数据。目标检查和写入同处节点数据库事务；外部文件或应用后续消失仍属于运行时变化，不能承诺永远可用。

D 根据 [Android 前台服务类型文档](https://developer.android.com/develop/background-work/services/fgs/service-types)，增加普通声明权限 `FOREGROUND_SERVICE_SPECIAL_USE` 和用途 property；API 34 以下仍调用原有双参数 startForeground。没有申请 VPN 授权、电池优化豁免或修改系统设置。Play 对 specialUse 的审核不属于本轮本地验证。

G 的节点数据库与选择配置数据库是两个数据库，没有宣称跨库原子性。节点删除提交后到选择清理前若进程死亡，既有启动入口 `ProfileManager.selectFirstIfNeeded` 会修复失效选择。

### 新增与扩展测试

| 测试类/文件 | 本轮实际结果与覆盖 |
|---|---|
| `SubscriptionRefreshRaceTest` | 8 项通过：旧结果遇链接/名称/自动更新变更、ABA、同配置乱序、删除、失败取消重试、并行自动/手动代次、流量 checkpoint/清零顺序、含依赖的混合批次保留数据库。使用真实 Room/SQLite，核心解析通过 Go host 执行。 |
| `SubscriptionMigrationTest` | 1 项通过：从仓库 v1 JSON schema 创建真实旧库和索引，迁移到 v2，由 Room 校验完整 schema，核对组、节点、规则及 tx/rx 保留。 |
| `SubscriptionBusinessBoundaryTest` | 3 项通过：非法协议、userinfo、端口与 content 输入不能新增/覆盖；HTTP、IDN、原始路径/query 编码创建与修改一致；旧 content 记录、授权缺失/有效/撤销/不匹配的边界。表单及外部入口均静态追踪到此共享业务入口，未冒充实际 UI 点击测试。 |
| `RuleSaveValidationTest` | 2 项通过，内部逐一覆盖端口 0/65536、源端口逆序区间、无效 IP/CIDR/正则/协议、空匹配、已删除目标、自定义 JSON；失败保留原记录，旧无效禁用规则不能启用；合法单端口和开放区间兼容。 |
| `ProfileAutoSelectionConcurrencyTest` | 11 项通过，新增 4 项清空分组用例，真实配置库与节点库验证无关分组、当前分组、删除失败、删除期间切换选择。 |
| `ServiceStopFailureTest` | 6 项通过，新增两模式类型选择及前台建立失败注入：原生 preInit 未执行、状态 Stopped、代理和通知清理、停止自身。 |
| `core/importer/dependency_regression_test.go` | 普通节点、空/错类型依赖、安全约束、未知普通注解、sing-box 绑定约束通过。B 的最小用例修复前确实失败：带 dialer-proxy 的节点被独立导入；修复后拒绝。 |
| `core/api/rule_validation_test.go` | 核心接口合法边界、开放区间、非法/空匹配、未知字段通过。 |
| `libcore/procfs/address_test.go`、`platform_owner_test.go` | IPv4、裸 IPv6、合法零/最大端口、负数、溢出，以及源/目标错误拒绝通过；不能据此声称旧 Android UID 分流真机通过。 |
| `TestDormantPreflightCannotBindOrDisruptRunningOwner` | 新增原生测试通过：占用的本地监听端口不影响未启动预检；临时关闭、无效正则预检均不改变 main 所有权，原运行实例随后完成真实 loopback HTTP 请求。 |

Kotlin 测试源码在 `app/src/test/java/io/nekohasekai/sagernet/`。受新 Ticket 接口影响的 Android instrumentation 用例同步更新并编译；`SubscriptionEndToEndNativeTest` 增加含 dialer-proxy 混合订阅保留原节点及元数据的断言，但本轮未在设备执行。

除 B 的修复前动态失败外，A/C/D/E/F/G 的修复前证据为当前基线源码调用链及最小输入分析，未伪称全部做过修复前真机复现。测试开发期间出现过类加载、夹具 schema 缺失索引、模拟配置读取旧关闭数据库等失败，均修正测试隔离或夹具后重新通过，未禁用测试或放宽业务断言。

### 上一轮四项回归

| 项目 | 结论与本轮证据 |
|---|---|
| 重活离开主线程 | 当前已修复并复核。BaseService 配置/初始化、launch、closeAndAwait 位于 IO；状态协调保留 Main，停止等待启动任务。ServiceStopFailureTest 及 ListenerStopFailureTest（8 项）通过，libcore 生命周期测试覆盖关闭、取消和旧实例回调。没有新增主线程阻塞时长真机测量。 |
| 编辑先保存后停止 | 当前已修复并复核。ProfileSettingsActivity.saveAndExit 先 serialize、原始配置原生预检、updateProfile，成功后才 stopService；保存事务从数据库重新读取流量，避免陈旧草稿覆盖 tx/rx。未改写此正确顺序。 |
| 完整原始配置预检 | 当前已修复并复核。ProxyInstance.buildConfigTmpAndValidate 在 IO 构造并关闭未启动实例；BaseService.reload 在候选通过前不碰运行实例。无效 reload 保留实例的 JVM 测试及上述新增原生占用端口/所有权/真实请求测试通过。 |
| 周期流量持久化 | 当前已修复并复核。30 秒 checkpoint、conflated 写唤醒、按节点合并待写、最终 drain 均保留。TrafficEfficiencyLifecycleTest 18 项通过，包含编辑、清零、监听失败不重复累计、最终刷新；新增订阅测试核对提交前后的增量及清零不被覆盖。 |

### 本地执行记录

使用仓库锁定 Go 1.27.1、Java 25 及既有 Android 构建流程。以下命令在仓库根执行；Android 命令使用已配置的 Java 25 JAVA_HOME，不含任何签名参数。

```sh
GOTOOLCHAIN=go1.27.1 go -C core test -mod=readonly -count=1 ./...
GOTOOLCHAIN=go1.27.1 go -C core test -mod=readonly -race -count=1 ./...
GOTOOLCHAIN=go1.27.1 go -C libcore test -mod=readonly -tags with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls -count=1 ./...
GOTOOLCHAIN=go1.27.1 go -C libcore test -mod=readonly -race -tags with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls -count=1 ./...
bash scripts/build-private-android.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/check-public-content.py
git diff --check
```

- core 普通与 race 全包测试通过，覆盖 api/compiler/importer/profile；core-host 没有自己的测试。
- libcore 普通全包测试通过；它与 core 分别执行，不互相替代。最终 race 结果见下方收尾记录。
- 原生 AAR 按既有中性路径构建脚本成功重建，Android 任务使用该 AAR。
- Android 全部任务成功，JUnit XML 汇总 **310 tests / 0 failures / 0 errors / 0 skipped**。
- Lint **0 errors / 0 warnings / 11 hints**。修复过程中补上了服务类型辅助方法的 API 34 注解，没有关闭检查。
- 上述结果来自本轮真实输出及 `app/build/test-results/testDebugUnitTest/TEST-*.xml`、`app/build/reports/lint-results-debug.txt`。这些生成报告保留在本地构建目录、未提交；此处保存其脱敏汇总，重跑命令可再生成。没有引用个人原始日志或不可核实的临时文件作为交付证据。

两次 libcore race 全量运行中，既有 `TestSubscriptionHTTPTimeoutIncludesBody` 在响应头阶段超过 100 ms，报 `context deadline exceeded`，不是 race detector 数据竞争报告。同一用例单独 `-race -run TestSubscriptionHTTPTimeoutIncludesBody -count=10` 全部通过。最终在该测试夹具计时开始前显式执行 `runtime.GC()`，使前序原生夹具的堆清理不挤入短请求预算；保留原 100 ms、真实 HTTP 服务、正文超时及服务器断开断言。随后全量 race 通过（libcore 107.650 s、procfs 2.398 s）。这个测试隔离调整没有修改生产 HTTP 逻辑。

### CI 与未验证范围

新增 `.github/workflows/android-contracts.yml`，覆盖 app/core/libcore、构建脚本、Gradle 配置变化。单 job 使用现有固定上游获取/补丁流程，独立执行 libcore 普通与 race 测试，再构建 AAR，随后执行 JVM、Lint、Debug 与 instrumentation APK 编译；不会因缺少 AAR 而直接跳过 app。现有 core workflow 保留其全包测试。新增 workflow 仅 contents:read，上传测试报告，不发布 APK，不修改 release workflow。已本地解析 YAML 并核对步骤顺序；由于没有 push，本轮没有新的 GitHub CI 运行结果。

设备查询开始出现过一个随后失效的 ADB 条目，shell 无法读取 SDK；重新查询并执行 `adb reconnect offline` 后，device/offline/unauthorized 均为 0。本轮没有可用真机，没有创建模拟器、安装 APK 或更改手机设置。因此 Android 14+ 无 VPN 授权纯代理启动、真实双进程刷新、旧 Android UID 分流、表单与生产 VPN 验收全部保留为未验证。Debug APK 构建成功不等于可发布的正式签名包；本轮没有签名或发布请求。

数据库唯一版本变化是节点库 **1 → 2**，只添加协调表与级联外键，既有表不重建、不 destructive migration。应用版本不变。升级后的数据库不能由只支持 schema 1 的旧二进制直接打开；回退安装需另行评估，不能删除用户数据库规避。

### 收尾记录

最终 libcore 普通与 race 全包检查通过；没有 race detector 报告。Android 最终 310 项 JVM 回归、Lint 和两个 Debug APK 构建通过。公开内容扫描为 0 项，diff 空白检查通过。未执行或无法执行的设备、远端 CI 和正式发布验收仍按上文保留，不因本地测试通过而改写为成功。

修复源码提交：`a7454682dd561f1dc793dab25bbda17c3866edd1`（`fix: guard subscription commits and validate runtime boundaries`）。本记录单独做文档提交，不再改动已验证代码；最终仓库 HEAD 为该文档提交，可由 `git log -1` 核对。结束时保持 `main`、单一分支及工作树、无未提交或未跟踪文件，没有夹带其他任务修改，没有 push。测试生成的临时 cache 数据库移入系统垃圾篓，未永久删除。应用仍为 2.0.8 / VERSION_CODE=96。

## 后续获授权发布：2.0.9

用户随后明确要求推送 GitHub，按既定规则同时交付正式安装包。由于 2.0.8 已公开，本次仅递增发布版本至 2.0.9 / VERSION_CODE=97（APK versionCode=485），此前七项修复代码不再变更。

- 使用已验证的原生 AAR；其隔离构建目录中的 54 个生产 Go 源文件与本次源码逐文件一致。
- `:app:assembleRelease :app:lintRelease` 成功，沿用仓库锁定工具链和默认压缩设置。
- 正式 `com.vialen.app` ARM64 APK 使用原 Release 密钥签名，证书与原公开签名证书一致；密钥和密码始终留在仓库外，未提交。
- 源码及签名 APK 的公开内容扫描均为 0 项；ELF 和 APK 的 16 KB 对齐检查通过。
- APK SHA-256：`bd8cf1b860e18f6de9cc205c730644b460f89eb7e425dd065dd031897429a7fc`。
- 本次发布不增加真机验收结论，沿用上述明确列出的未验证范围；安装包未预置个人节点、账号或订阅。
- 首次远端 Android CI 在工具链准备阶段因 sdkmanager 不在 PATH 失败，未执行测试。后续仅修正共享准备脚本，从既有 ANDROID_HOME 定位命令行工具；未修改 SDK/NDK/Java/Go 版本或正式 APK。

## 规则集元数据校验与 CI 准备阶段复核

复核基线为 `69827f0`。规则保存原先仅向核心提交 Match，遗漏规则集元数据；新增 JVM 用例在修复前实际失败（编辑器接受 JSON 链接搭配 binary）。本次仅补齐此缺口，不重做已成立的另外六项修复。

- 编辑器、规则创建、修改、重新启用都向核心提交规则集 URL/路径/格式；核心校验直接复用运行编译的 `prepareRuleSets`，不下载文件或启动实例。
- 四类异常（JSON+binary、端口 65536、端口 0、无路径）均拒绝；SRS+binary、JSON+source 均接受。新增测试同时检查保存失败原记录不变、旧无效记录不能重新启用。
- core 普通与 race 全包测试通过。重建原生 AAR 后，Android 全部 311 项 JVM 测试通过，0 failures/errors/skipped；Lint 0 errors、0 warnings、11 hints；Debug 和 instrumentation APK 编译通过。
- 工具链准备脚本改为逐项输出检查名称、期望和实际值，报告从准备开始即保留。锁定 SDK 平台属性为 `37.0`，原断言误要求 `37`，已修正，未升级工具链。
- 三项脚本回归覆盖成功、版本不匹配和安装失败。远端 Linux Bash 另暴露函数内 ERR trap 未继承的问题，已使用 errtrace 修正，未放宽失败断言。
- 本轮没有新增真机验收结论；此前设备相关未验证项仍保留。正式发布版本递增至 2.1 / VERSION_CODE=98（APK versionCode=490）。

补充验证记录：

- CI 工具链修正提交 `2b3cd82` 的完整 Android/libcore 运行 [34897559316](https://github.com/killertop/Vialen/actions/runs/34897559316) 成功，所有测试和编译步骤均实际执行，报告上传成功；远端 SDK 检查明确输出 expected=37.0、actual=37.0。
- 本地额外两次 libcore race 全量检查暴露既有正文超时测试的隔离问题：在响应头阶段提前超过 100 ms；单独连续十次通过。原先仅调用 GC 不足以消除全量运行影响，改为在独立的同一测试二进制进程内执行，保留 race 插桩、真实 HTTP、100 ms 期限、正文超时及服务器断开断言，子进程失败会使父测试失败。此变更仅影响测试，不改变 APK。
- 调整后 libcore 普通及 race 全包测试均通过（race：libcore 96.989 s、procfs 2.350 s），没有数据竞争报告。上述失败记录保留，不以通过的重跑覆盖历史。
- 正式 2.1 APK 的签名与原公开证书一致，包名 com.vialen.app、versionCode=490；源码和签名 APK 扫描均为 0 项，16 KB 对齐全部通过。SHA-256：`1e2722bc34b1e66b29e19f81563bc84ecf717bb3b09e34a5a24288df583a8d15`。

最终远端闭环：

- 发布源码 `e54284a611c89415e9dd32ce4825fe7cf8e768b2` 的 [Android/libcore CI 34898245206](https://github.com/killertop/Vialen/actions/runs/34898245206) 全部成功；下载报告核对 311 tests / 0 failures / 0 errors / 0 skipped。Go Core Contracts 和公开内容检查亦成功。
- 含测试隔离调整的 `50d4599cd96b266ce176f4a2194b145a7d4e3b5f` 的 [完整 CI 34898608068](https://github.com/killertop/Vialen/actions/runs/34898608068) 成功：工具链、libcore 普通/race、原生 AAR、JVM、Lint、两个 Debug APK 和报告上传全部实际执行。
- [Vialen 2.1](https://github.com/killertop/Vialen/releases/tag/v2.1) 已公开，标签固定在发布源码 e54284a；后续提交只调整测试和本文档。GitHub 附件摘要与上文 SHA-256 一致，唯一附件为正式 ARM64 APK。中性构建目录内 62 个生产 Go 源文件与发布源码逐文件一致。
- 本次两个待修项已关闭；历史真机未验证项不在本结论内。测试缓存数据库已移入系统垃圾篓，仓库仅保留 main 和单一工作树。
