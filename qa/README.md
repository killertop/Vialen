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

## 2.1 正式包真机验收

使用已连接的 Android API 37 真机。核对原装 2.0.4 与 2.1 签名证书一致后，保留数据升级至正式 com.vialen.app 2.1 / versionCode 490。未卸载或清除正式应用数据，未修改 Wi-Fi、分辨率、显示密度或字号。

- 升级后原节点和四条路由均保留。四条规则逐条关闭、重新开启成功，结束时恢复全部开启；测试草稿均放弃，没有向正式路由列表保存合成规则。
- `ProductionRuleMetadataUiTest` 在正式应用未保存的 QAValidation 草稿上执行；通过独立测试 APK 的系统无障碍文本接口填入准确 URL，不启动测试版 VPN。四种异常（JSON+binary、65536 端口、0 端口、无路径）均保留编辑器并显示错误；两轮执行分别核对 JSON+source、SRS+binary 被接受，结果各 OK (1 test)。合法输入只进入未保存草稿，不据此宣称已测试实际下载或数据库写入。
- 用例需显式 productionDraftAcceptance=1；默认跳过，且检查当前包名及合成草稿名。validFormat=binary 切换合法 SRS 场景。先手工进入“新建规则 → 目标规则集 → 添加规则集 → HTTPS 规则集地址”，填写合成名称 QAValidation，执行后放弃外层草稿。
- 自动化初期系统输入法转换 URL 标点，故未把该轮输入作为产品验证；改用直接文本接口后四种异常均拒绝。随后修复测试对下拉窗口切换及无障碍树初始就绪的等待，最终两轮通过，未修改生产校验逻辑。
- 正式 VPN 系统授权、启动成功，前台服务正常；主动点击连接测试，观察“测速中”后返回 261 ms，同时出现收发流量。停止后回读“未连接”，服务不再处于前台。检查到的崩溃缓冲中没有正式应用崩溃记录；未进行长期稳定性测试。结束时保持原先未连接状态。
- 文案待改进：上述规则集参数错误统一显示“规则文件无效，请重新下载”，拒绝行为正确，但针对链接/端口/格式的处理建议不够准确。本轮仅记录，未变更生产版本。
- 原始截图、UI 树和日志均保留在仓库外，未提交。当前设备不能证明旧 Android UID 分流；纯代理无 VPN 授权、真实双进程订阅竞争、长期 checkpoint 异常退出等历史专项仍不视为本轮已验证。

本轮只新增可复用验收用例和脱敏记录，应用版本保持 2.1，不重新发布 APK。

### 规则集错误提示改进

后续按用户要求，将规则集兜底错误提示改为“规则集无效，请检查地址和格式”，不再要求用户一律重新下载。适用于本次发现的链接、端口、路径和格式校验错误，底层校验逻辑保持不变。版本递增至 2.1.1 / VERSION_CODE=99；本项为文案改动，未重新声明正式包真机验收。

## 2.1.2：旧管理与测速入口五项修复

复核基线 `1020530092d10d333161f86988fae8eb1f5d9a00`。五项调用链问题均在源码中确认；此前规则集元数据校验和 CI 阻塞继续关闭。本轮没有改动 Go 核心或重写已验证的运行编译流程。

| 项目 | 修复与证据 |
|---|---|
| 手动去重误删 | 普通节点复用订阅的完整 Profile 连接语义，只忽略 id/name。链与原始配置保守保留，不自动去重。测试覆盖凭据、TLS、传输差异、端点拼接碰撞及仅名称不同；删除事务还重新核对候选文档、分组和仍存在的保留副本，确认期间被修改或已成为最后副本时不删。 |
| 测速整行写回 | TCP、URL 结果只写 status/ping/error，并通过 document 条件拒绝配置已变化的旧结果；清除结果使用组内字段级 UPDATE。真实 Room 测试按旧快照→配置与流量提交→测速保存/清除的顺序验证配置保留、tx 从 100 经 200 与 50 增量达到 350，未回退。没有数据库 schema 变化。 |
| 空网络与取消 | TCP 使用同一个经过空值检查的 Network 快照完成 DNS/建连，无网络返回中性提示。复用批次协程的取消、等待和稳定结果保存；会话 finally 清理通知、对话框、运行标志。新增空网络、DNS 不打开 socket、取消关闭 socket 并等待工作退出测试，既有批次失败/取消回归一并通过。 |
| 原始配置独立测速 | 完整 raw config 在测速快照阶段明确拒绝，显示“该配置不支持独立测速”，不启动第二个实例。中性结果不算不可用，也不进入“删除不可用节点”。真机启动一个真实 mixed 监听，调用 TestInstance 收到预期拒绝后，再次完成 SOCKS 握手，原实例正常，最后关闭该合成实例。 |
| 外部链接 | 增加 vless/hysteria2/hy2/tuic/anytls，修正 socks5/socks4a 拼写。真机由核心导出六种合成 URI，用 PackageManager 验证隐式 ACTION_VIEW/BROWSABLE 能解析到当前测试包的 MainActivity；普通 HTTP/HTTPS 不被截获。未打开个人链接或写入用户节点。 |

验证命令（沿用锁定 Java 25 与工具链）：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w -r -e class io.nekohasekai.sagernet.LegacyBoundaryNativeTest,io.nekohasekai.sagernet.ConnectionTestPersistenceNativeTest com.vialen.app.debug.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/check-public-content.py
git diff --check
```

- 最终 JUnit XML 汇总 318 tests / 0 failures / 0 errors / 0 skipped；Lint 0 errors / 0 warnings / 11 hints；两个 Debug APK 编译成功。
- 连接真机上的 5 项 instrumentation 专项通过，使用隔离测试包、内存数据库和合成回环监听，不是对正式安装包全部界面的验收。没有关闭 Wi-Fi、变更显示参数、创建模拟器或操作个人节点删除。原始测试输出保留在仓库外。
- 版本递增至 2.1.2 / VERSION_CODE=100。本轮未推送、未发布 APK，也未替换手机上的正式包。旧 Android、长期网络切换与全部页面交互不因这些测试转为通过。

## 第一批：数据一致性与线程安全

### 范围和基线

- 起点：main，`b85c21762f14ca7915bb85064be855ac84f9290c`，Vialen 2.1.2 / VERSION_CODE=100。开始时没有已有修改，只有 main 和单一工作树。
- 本批只修改删除/排序、进程内包缓存，以及清流量调用链。不升级版本、工具链、依赖或数据库 schema，不修改签名和生产配置，不推送、打标签或发布。
- 本文件只记录合成夹具和脱敏结果；原始构建输出、测试 XML 和设备输出不纳入 Git。

### A：删除与排序（确认并修复）

根因：GroupManager.rearrange 读取旧整行后通过列表 UPDATE 回写；撤销栏提交逐节点删除，每项重新排序。删除不可用节点还有独立的逐项删除入口。拖拽已经字段级写入，但整批排序没有事务保护。

- ProxyEntity.Dao.rearrange 只读取有序 ID，并仅更新 userOrder。普通删除与不可用节点删除统一通过 deleteProfiles；同一 Room 事务内重新读取当前记录、核对组归属和适用的文档/不可用状态，删除后每组重排一次。手动去重的独立复核入口保持不变。
- 拖拽保留原 order 值，使用 updateOrders 的同库事务；当前已移动到其他组的记录不受旧拖拽影响。任一删除/排序 SQL 失败会回滚本批。
- 撤销栏仍按原来的操作代次确定一个批次；撤销不会调用数据库删除，已退役的 Snackbar 回调不会提交后续操作。
- 只有数据库提交后才调用选择条件更新与 onRemoved。保留现有各消费者的逐节点事件，不在本批引入新的列表事件协议。
- **跨库边界**：节点库和 PublicDatabase 是两个数据库。节点提交后调用既有 clearDeletedSelection；它不会清除用户刚切换的新选择。进程在两库之间退出的恢复仍依靠既有 selectFirstIfNeeded，不能宣称跨库原子提交。
- 取消边界：进入事务前响应取消；已接受的操作完成删除、选择与通知后退出，不因页面离开产生半批写入。

修复前动态证据：OrderConsistencyTest 在真实 Room/SQLite 的 BEFORE UPDATE 触发器中安排配置、增量/清零流量和测速更新；旧实现实际失败，配置 expected=new、actual=old。触发器是确定性同库交错，并非声称复现了两条真实订阅/统计进程。

修复后：上述字段全部保留；删除、重排与拖拽故障触发器验证整批回滚；跨组、错误组归属、不可用节点结果/文档变化、撤销与迟到回调、删除当前选择/无关组/切换新选择、接受后取消均有回归。宿主真实 SQLite 计数夹具：8 行跨两组，删除 3 行，剩余 5 行各发生一次排序更新（总数=5，distinct ID=5）。真机合成夹具删除两行后，剩余两行各更新一次。这些是夹具中的 SQL 行更新计数，不是 fsync、耗电或大数据性能结论。

### B：PackageCache（确认并修复）

根因：多个映射分别赋值，UID 的 HashMap/HashSet 原地清空并填充，读者可能观察中间状态。旧失败处理还会清空可用缓存。

- PackageSnapshot 在局部完成全部索引；SnapshotLoader 通过 volatile 引用一次发布。外层映射和 UID 的内部集合不可修改；Android PackageInfo/ApplicationInfo 使用 Parcel 深拷贝保护，消费者取得的可变元数据也是独立副本。
- 完整扫描串行执行；事件通过单一、有界合并信号交给 IO worker，不增加轮询。较早扫描无法在较新扫描之后完成发布。
- 注册监听发生在初始扫描前；安装/卸载（含替换的移除/新增事件）继续触发刷新。初始失败总会释放等待信号并给出明确异常；刷新失败保留旧快照及失败信息，不发布空映射。
- 配置构建、规则校验和原生包/UID 查询各自固定一份快照。未知包不再默认为 UID 0。应用列表首次失败显示中文提示；仅名称显示允许回退包名，这不参与路由 UID 决策。
- 这是**进程内**一致性，两个进程各自维护缓存；不是跨进程共享内存，也不宣称两次 Android PackageManager 调用构成系统级事务。

修复前证据为源码调用链，未补写修复前动态复现。回归覆盖：并发刷新/查询不混合映射版本、第二次扫描不能越过被 barrier 阻塞的第一次扫描、失败保留旧快照、初次失败等待者终止、共享 UID、安装/卸载/替换的合成版本变化、源集合及消费者修改不污染已发布结果。因选择串行方案，逆序完成被禁止，而非放任逆序后才覆盖检查。

### C：清流量（确认并修复）

根因：Binder.clearTraffic 切到 Main 后执行同步 DAO；连接态还持有统计 monitor 写数据库，主线程选择回调可能等待该 monitor。

- 同步 Binder 返回值保持不变，工作在 IO 调度器完成；本地 Main 线程错误调用直接返回 false，不排队伪报成功。实际界面调用原本就在工作线程。
- 锁顺序：进程级 trafficOperations → looper persistenceMutex → 短暂统计 monitor。初始化、关闭与 Binder 清零共享前者；清零不在统计 monitor 内等待数据库或通知，也不反向同步等待 Main。
- 清零边界：持统计锁采样并捕获各归属累计值，随后释放统计锁进行 SQL 清零。提交成功后扣除捕获值，保留期间新采样字节；持久化基线改为零。已取出的旧队列任务只会在 persistenceMutex 释放后读取新累计值，不会恢复历史字节。所有出站、链路归属和最终刷新路径保留。
- SQL 失败不改变持久化基线，已采样字节留待后续 checkpoint/最终刷新；已接受操作在取消后仍完成提交与基线调整并可 join。通知失败不把成功提交误报为数据库失败。
- 请求排队前后核对 Binder 所属 Data、状态代次及代理实例。停止后旧 looper 拒绝清零。初始化在同一门控内重读主节点已提交流量，避免沿用停止态清零前的计数。
- 不移除 allowMainThreadQueries、不切换日志模式。其他入口的同步数据库查询仍是后续范围，不宣称本批完成全项目主线程数据库迁移。

修复前证据为源码调用链。回归覆盖 DAO 非 Main、数据库被 latch 阻塞时选择仍能完成、排队 checkpoint/清零/新增流量/最终刷新、停止与清零交错、实例失效、状态代次变化、SQL 失败返回 false、重复及并发清零、调用方取消后已接受事务完成。历史流量归属和停止失败传播测试一并执行。

### 执行与结果

沿用锁定 Java 25.0.2，SDK 37.0 和现有构建流程；没有修改原生接口声明或 Go 核心，本批不重建原生 AAR、不重复运行 Go/race。

```sh
# 修复前：实际失败一次，随后相同断言保留。
./gradlew :app:testDebugUnitTest --tests '*OrderConsistencyTest'
# 最终完整验证。
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# 隔离真机专项。
adb shell am instrument -w -r -e class io.nekohasekai.sagernet.BatchConsistencyNativeTest,io.nekohasekai.sagernet.ConnectionTestPersistenceNativeTest com.vialen.app.debug.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/check-public-content.py
git diff --check
```

- JVM/Room：337 tests / 0 failures / 0 errors / 0 skipped，包含本批新增 19 项；测试使用真实 Room/SQLite，服务/原生计数等外部边界按各测试说明控制。
- Lint：0 errors、0 warnings、11 hints。Debug 与 instrumentation APK 构建通过；仅安装 com.vialen.app.debug 和对应测试 APK。
- 真机：BatchConsistencyNativeTest 的排序字段隔离/回滚、快照发布/失败、清零与主线程选择/最终刷新，以及既有 ConnectionTestPersistenceNativeTest 两项，共 5 项通过；最终代码构建的隔离 APK 重新安装后再跑同组 5 项，仍全部通过。清流量使用真实 Binder 实现的进程内同步入口、真实 Room 和 Android 主线程，计数器为合成可控输入，不启动 Android VPN 或转发核心。
- 真机曾短暂断开，第一次安装命令失败；恢复连接后两次隔离 APK 安装成功，并执行上述专项。没有替换正式包、清正式数据、关闭 Wi-Fi 或改变显示参数。
- 过程失败如实保留：最初新增选择测试漏导入 coEvery，编译失败后补齐导入；宿主故障注入触发 Android Go 日志初始化失败，仅 mock 日志边界，保留真实 SQL 故障断言；一次全量回归中两个既有关闭失败用例因 strict mock Data 上新增锁 getter 失败，生产锁改为真正进程级入口后原断言通过。没有删除用例、放宽业务断言或升级依赖。

### 验收界限

三项源码缺陷及本批可控并发回归可关闭。仍未覆盖：不同 Android/OEM 的真实安装、卸载、替换广播风暴；实际跨进程 Binder 调用方死亡；系统杀进程以及真实 Android VPN 服务启动/重启的故障注入。这些未验证项不能用宿主或进程内测试替代。

具备进入第二批列表/统计优化的代码基础；第二批须保留本批与历史全部统计、取消、预检和最终刷新回归。不报告省电比例，也不使用旧报告的 94,950 或 4.96 倍作为本批实测收益。

### 本地提交

- `9ba9cdfd2e95a01192210b206e56038d18c243d4`：删除与排序同库事务、选择条件更新及撤销回归。
- `716129163c9b4cf2fee2d42f19215b05d30f02a5`：不可变包快照、串行刷新和同版本消费者。
- `e6a0371046babadeec1c61fb879f058e3e770ba6`：清流量 IO、统计边界及服务实例门控。
- 代码收尾仍为 main、2.1.2 / VERSION_CODE=100，无 schema、原生核心、工具链、签名或发布配置变化。QA 记录单独提交；最终状态在任务回复中回读。

## 第二批：高频列表与统计优化

### 基线与边界

起点为 main / `c3ade8f37a390ecc195ccb9704ae042c1df261c1`，Vialen 2.1.2 / VERSION_CODE=100，单一工作树且开始时无修改。本批不升版本、不推送、不发布，不涉及 GC、日志、订阅调度、MTU、转发栈或数据库 schema。原始输出、设备资料和测试生成数据库均保存在仓库外。

独立复核确认：采样已对有效 tag 去重并跳过 ignore，旧跨语言调用数是有效唯一 tag 数乘以二；后台已有降频。本批没有把这两点当成缺陷重写。剩余重复工作包括逐 tag JNI、逐节点前台 Binder、流量完整绑定、主线程 DiffUtil、Kryo 转 List<Byte>、逐次排队的纯读取及导入后逐节点选择检查。

### 实现与一致性边界

- 原生增加 QueryStatsBatch：一次传入 tag 列表，返回按序的小端 tx/rx 整数对。重复 tag 只清读一次；关闭后仍可读取最终计数。Kotlin 一轮调用一次批量接口，保留全部 tag 归属、速率和累计语义。Go 内部依然读取每个有效 tag 的两个计数器，不能宣称这些内部查询消失。
- 前台按累计值变化通知，每批最多 256 个纯数字 TrafficData；新消费者注册或从后台回到前台触发完整快照。速度独立更新；停止和清零继续沿用已有最终通知与持久化顺序。AIDL 追加方法且保留旧方法，客户端和服务随同一个 APK 更新；没有对外跨版本 RPC 兼容承诺。
- UI 批量与旧单条回调共用同一 Main 路径，避免额外 Default 跳转带来的顺序交错；每批只切入 Main 一次；流量只绑定流量文本及相关可见区域，不重设点击、名称、类型或异步选择状态。首批数据可在列表加载前缓存，内容更新保留实时统计，重绑定能取回缓存。
- 列表内容快照复用持久化 document 字符串和显示状态字段，去掉 Kryo/字节装箱集合；保留完整 document 的比较以使隐藏配置变化更新编辑/分享目标。流量独立比较。DiffUtil 在 Default worker 上计算，Main 捕获旧状态和提交结果；代次与视图身份复核保留。
- OrderedWorkQueue 仅合并同键的待处理纯读取；写操作仍逐项顺序执行。正在执行的旧读取在查询前及发布前检查代次，视图销毁取消待处理读取，已接受删除/排序不随视图销毁取消。
- 批量导入仍在原有 Room 事务内检查目标并写入全部节点；提交后检查一次默认选择并发一个批次事件。兼容 Listener 默认分发保留，列表消费者覆盖批次入口。节点库与选择偏好库仍为不同数据库，保留条件选择逻辑，没有扩大事务承诺。

### 验证

修复前证据为本轮源码调用链，未声称完成修复前 Android 动态性能测量。

- `JAVA_HOME=<锁定 JDK> ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`：通过。343 tests / 0 failures / 0 errors / 0 skipped；Lint 0 errors / 0 warnings / 11 原有 hints。
- 新增 TrafficBatchTest：唯一有效 tag、ignore、零时间间隔、累计只消费一次、新消费者完整快照、零值重置、分片及轻量内容快照。新增队列测试使用明确门控，1,000 个待处理同键读取实际执行 1 次，10 个写操作全部按序完成；失效视图读取取消不影响写入及另一分组。
- 合成统计夹具：10,000 个有效唯一 tag，每轮一次批量传输，返回 160,000 字节；两轮未重复累计。10,000 行完整通知分成 40 片，无变化下一轮为 0 行。这些是测试接口计数，不是 JNI 耗时、Android 分配量或耗电测量。
- 在 libcore 目录执行 `GOTOOLCHAIN=go1.27.1 go test -tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls ./...` 及相同标签的 `go test -race`：均通过。新增真实 HTTP 回环测试验证关闭后最终流量、重复 tag、缺失 tag、第二次读取清零。
- `JAVA_HOME=<锁定 JDK> bash scripts/build-private-android.sh`：中性目录重建 AAR 成功。原生测试使用现有生产标签；最初从仓库根目录运行 Go 命令无模块，以及省略构建标签的尝试失败，随后按工作流标签重跑通过。首次 Kotlin 编译发现协程接收者与私有缓存访问错误，修正后上述全量命令通过；没有删除失败用例或放宽断言。
- `git diff --check` 与 `python3 scripts/check-public-content.py`：通过，公开内容 0 findings。

USB 接入前的历史阻塞（现已解除）：设备初始在线，流式安装长时间未完成；重试非流式安装返回连接关闭，随后 ADB 无设备。用户提供地址后重连成功，但从固定仓库外副本进行的非流式安装在 180 秒内仍未完成；窗口查询也超时。没有安装成功回执，因此未启动 instrumentation，不以旧包或 JVM 结果替代。没有卸载正式包、清用户数据、切换 Wi-Fi/显示设置或启动 VPN。

当时已编译但尚未执行的真机用例：TrafficBatchNativeTest（真实 HTTP 回环/JNI 最终计数、重复 tag、Parcel 分片上限、1,000 节点 Room 导入单次已提交事件及手动选择）；FormLifecycleNativeTest 的 lastNodeDeleteUndoAndCommitRefreshConnectionControls 新增流量变化/归零不重绑名称断言；本轮尚未重跑 BatchConsistencyNativeTest、TrafficEfficiencyNativeTest 与 SelectorCallbackNativeTest。第一批既有真机通过记录保留，但不算第二批重新通过。

恢复稳定真机连接后的命令：先安装本轮已构建的 `.debug` APK 与其 androidTest APK，再用 `adb shell am instrument -w -e class io.nekohasekai.sagernet.TrafficBatchNativeTest,io.nekohasekai.sagernet.BatchConsistencyNativeTest,io.nekohasekai.sagernet.TrafficEfficiencyNativeTest,io.nekohasekai.sagernet.SelectorCallbackNativeTest com.vialen.app.debug.test/androidx.test.runner.AndroidJUnitRunner`；表单专项单独运行，传入 `-e vialenForms true -e class io.nekohasekai.sagernet.FormLifecycleNativeTest#lastNodeDeleteUndoAndCommitRefreshConnectionControls`。仅限隔离包与合成数据。

尚未测量 1,000/10,000 节点滚动帧耗时、CPU/能耗、多消费者 Binder 进程死亡压力；不据功能测试推断省电百分比。

USB 接入前结论：第二批代码与宿主回归完成，可本地提交；当时真机验收尚不能关闭，不能声称已验证 Android 性能或功耗收益。本批只在 main 留本地提交，版本及发布状态不变。

本地实现提交：`405033c`（原生批量计数接口与回环回归）、`0794707`（Android 增量通知、列表/队列/导入优化及回归）。实现提交后工作树仅剩本节 QA 记录，记录单独提交；没有夹带既有修改。

### USB 真机续验

- USB 识别及实际 shell 响应成功，隔离 `.debug` 应用和测试 APK 均取得安装成功回执。正式包、Wi-Fi、显示参数和生产 VPN 未操作。
- 首轮 8 项中 7 通过、1 失败：新增 HTTP 回环夹具只接受一个连接，而 RTT 路径还会发起后续请求，导致等待响应头超时。为新用例补上既有 BenchmarkForegroundRule 后仍复现同一超时；因此不能仅归因于后台限制。将夹具改为接受连续连接，显式关闭 listener 后 join worker，保留原有超时、最终流量、重复 tag、二次清读和 Parcel 上限断言。仅改测试夹具，未改生产代码或放宽断言。
- `:app:assembleDebugAndroidTest` 通过；通过 USB 更新测试包。TrafficBatchNativeTest 2 项、BatchConsistencyNativeTest 3 项、TrafficEfficiencyNativeTest 1 项、SelectorCallbackNativeTest 2 项，共 **8 项通过**，运行报告 `OK (8 tests)`。
- 列表界面专项 `FormLifecycleNativeTest#lastNodeDeleteUndoAndCommitRefreshConnectionControls` 通过：非零流量及清零保持名称绑定，最后节点删除、撤销、提交及连接按钮状态正确。另以 `am instrument -w -r` 核对完成状态码为 **0**，不是 assumption 跳过；报告 `OK (1 test)`。
- 原始运行输出保存在仓库外，QA 不包含设备标识或调试地址。此次仅新增测试夹具修正和本记录，无生产代码变化，因此未重复此前已通过的 343 项 JVM、Go 普通/race 和 Lint。

续验结论：第二批本次安排的 **9 项真机功能专项已通过**，安装阻塞已通过 USB 绕过，本批已安排的功能验收待办可以关闭（不代表无线传输已修复）。1,000/10,000 节点帧耗时、CPU/能耗及多消费者 Binder 进程死亡压力仍未测量，不作性能或省电量化结论。

### 两批合并发布：2.1.3

用户另行授权将上述两批合并推送并发布 2.1.3，解除此前仅本地提交/不升版的限制。发布准备起点 `1e44b10`；只递增版本到 VERSION_NAME=2.1.3、VERSION_CODE=101（ARM64 APK=505），不改变生产逻辑。

Release 构建命令 `JAVA_HOME=<锁定 JDK> ./gradlew :app:assembleRelease` 通过。使用仓库外原有正式签名，apksigner 验证通过且证书与 GitHub v2.1.2 APK 相同；源码与正式 APK 的公开内容检查均为 0 findings，ELF LOAD 与 APK ZIP 16 KB 对齐通过。最初签名命令重复从同一单行密码文件读取两次，遇到 EOF；移除重复 key-password 参数、复用同一密钥口令后成功，未修改凭据。

附件：Vialen-2.1.3-arm64-v8a.apk；SHA-256 `1e8555843ff409037480cacf48ea09998917ec3b3facb8dd240746e96c5d346b`。仅上传正式 APK，不上传密钥、日志、测试数据库或设备资料。正式 2.1.3 APK 未安装到生产包，真机结论来自相同生产逻辑的隔离测试包。

两批结果汇总：第一批三项缺陷及可控并发回归关闭；第二批实现和本次 9 项真机功能专项关闭。337 与 343 是先后两次全量 JVM 数量，不得相加；第二批 343 已包含历史回归。不同 OEM 事件风暴、系统杀进程/真实跨进程 Binder 调用方死亡、正式 Android VPN 启停故障注入，以及大列表帧耗时/功耗测量仍未完成。

## 第三批：后台订阅调度与按需内存回收

### 基线、范围及独立复核

起点 main / `79533396032099f5c02eb2c344ad36af03c0d627`，Vialen 2.1.3 / VERSION_CODE=101（ARM64=505），单一工作树，开始时无已有修改。本批保持版本、schema、工具链、生产依赖、签名和发布状态不变，只做本地提交。测试新增同版本 WorkManager 2.11.2 的 work-testing，不升级运行依赖。原始输出、设备资料、测试数据库在仓库外。

修复前证据为本轮源码调用链，未声称完成旧版本动态功耗或故障复现。确认旧全局周期任务缺少联网约束，Boolean 汇总导致一个失败触发整批 retry；文档和网络来源共用调度。确认 onTrimMemory 不区分级别调用 ForceGc，原生每次请求新建 goroutine 执行 FreeOSMemory。SQLite 配置版本/请求代次、成功提交时间、HTTP 正文取消与大小限制，以及前两批统计/列表/数据库保护已有正确机制，予以保留。

开始时重新读取远端基线工作流及实际步骤：Android/libcore [34953029101](https://github.com/killertop/Vialen/actions/runs/34953029101)、Go [34953029160](https://github.com/killertop/Vialen/actions/runs/34953029160)、公开内容 [34953029233](https://github.com/killertop/Vialen/actions/runs/34953029233) 均 success。Android 工具链准备、libcore 普通/race、AAR、JVM/Lint/测试 APK 步骤实际成功；下载基线报告解析为 343 tests / 0 failures / 0 errors / 0 skipped。Go 工作流的业务测试与 race、公开内容的拒绝私密材料步骤也实际成功。这些结果只证明旧基线，不能证明本批未推送的提交。

### A：确认并优化自动订阅调度

- `SubscriptionSchedule` 为每个自动订阅生成独立周期任务：网络来源 CONNECTED，content 来源 NOT_REQUIRED，不增加 Wi-Fi-only/充电/电量限制。沿用用户间隔及 WorkManager 的 15 分钟下限；输入只有分组 ID 和配置摘要，任务名称不携带原始链接。摘要覆盖来源、更新间隔和用户控制项，不包含 lastUpdated 等远端元数据。
- `SubscriptionUpdater.reconfigureUpdaterOrThrow` 保留进程 Mutex、跨进程文件锁、远端操作完成确认和超时。取消旧 `SubscriptionUpdater` 唯一任务；查询新标签，仅取消失效配置/已删除/关闭自动更新的任务，按需 enqueueUniquePeriodicWork(UPDATE)。配置不变时保留现有任务及下次执行时间，避免 UPDATE 保留原 enqueue time 而重新计算 initialDelay 造成时间偏移。配置改变时必须使用新身份，以停止仍按旧来源约束运行的实例；没有无故重建全部任务。持锁读取后若配置又改变，执行前及提交事务仍会拒绝过期结果，下一次重配置负责收敛调度。
- 旧持久化 Worker 缺少新输入时只执行有界迁移，不再下载全组。取消旧 WorkSpec 的完成回执证明调度状态已更新，不冒充所有旧协程已经退出；HTTP 协程另有取消与 IO 子任务 join。每次运行使用独立通知标识，即使同一 WorkSpec 的旧尝试延迟清理，也不能取消新尝试的通知。
- `SubscriptionRun` / `GroupUpdater.executeUpdateResult` 明确区分 UPDATED、SKIPPED、SUPERSEDED、TEMPORARY_FAILURE、PERMANENT_FAILURE。Worker 重读分组并检查来源摘要、自动更新、到期及仅连接时条件。`SubscriptionRefresh.begin` 在同一个 Room 事务内、递增请求代次之前复核预期配置，防止旧排队任务反过来使有效的新刷新失效。既有配置版本、请求代次及提交事务检查仍保留，网络约束不替代它们。
- 临时失败每个周期最多 3 次尝试，交给 WorkManager 30 秒起的指数退避；没有自建重试循环。格式、明确权限、HTTP 拒绝、证书和超大正文等错误本轮不立即重试。正常周期仍可再次尝试，配置修改/主动刷新也可重试；不修改 autoUpdate，不把失败时间写成 lastUpdated。成功、永久错误和过期任务互不拖入其他订阅的退避。
- 周期任务的 success/failure 不是一次性任务的终止状态；本实现非临时结果返回 success 结束当前 occurrence，之后仍 ENQUEUED 等待正常周期。语义依据 [Android 周期工作状态](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/states)、[更新已有工作](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/update-work)，并由当前 2.11.2 TestDriver 验证 success/retry 后的实际状态。
- `SubscriptionFetch` 使用新增 Go 结构化结果，不按异常 message 或中文文案分类。失败仅返回稳定代码，不带 URL、token、正文或响应头；正常结果保留必要订阅元数据。CancellationException 继续传播；HTTP 取消进入真实请求/正文并等待子任务退出，30 秒超时与 16 MiB 上限保留。
- `SubscriptionDocument` 向 ContentResolver 传 CancellationSignal，取消时关闭已打开流，IO 子任务随调用作用域结束。SecurityException/FileNotFoundException 属本轮不立即重试，普通 IO 可临时重试；content 提供者可能依赖远端，不保证离线一定成功。合作式 Provider 的打开取消已验证；无法强制保证任意第三方 Provider 响应 CancellationSignal 或跨线程 close，相关设备边界见下文。
- 通知权限不存在时不影响更新；通知、页面反馈失败不把已经提交的更新改报为数据库失败。提交后默认选择恢复属于另一个数据库边界，反馈失败不回滚已提交节点事务，也没有声称两个数据库原子提交。

### B：确认并优化主动 GC

- `MemoryTrimPolicy` 明确排除 UI_HIDDEN；BACKGROUND 只在回调当时 ActivityManager.MemoryInfo.lowMemory 为真时请求。API 34 以前另接受 RUNNING_CRITICAL/MODERATE/COMPLETE；新 SDK 不依赖这些已经停止通知的旧压力级别。依据 [ComponentCallbacks2 官方说明](https://developer.android.com/reference/android/content/ComponentCallbacks2)。没有增加轮询；不清空 UID 快照、运行配置、订阅状态或待写流量。
- 全部生产调用链仍只有 SagerNet.onTrimMemory → Libcore.forceGc → Go ForceGc；统一 `gcGate` 在启动 goroutine 之前持锁检查 busy/冷却。同进程最多一个主动回收，无排队/永久 ticker，拒绝请求不创建等待 goroutine。time.Now 的单调分量用于时间差；收集结束或可恢复 panic 均释放门控。
- 默认冷却 5 分钟，从接受请求时起算，是限制重复主动请求的保守预算，不是实测最优阈值；严重压力请求也不无限绕过冷却。Go 正常 GC 不受此门控限制，没有修改 GOGC/GOMEMLIMIT，没有同时 System.gc。UI 和 :bg 各有自己的 Go 堆与进程内门控，不是跨进程共享回收协调。

### 本地验证及过程中失败

实际命令（使用仓库锁定工具链）：

```text
JAVA_HOME=<锁定 JDK> bash scripts/build-private-android.sh
JAVA_HOME=<锁定 JDK> ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# core 目录
GOTOOLCHAIN=go1.27.1 go test -count=1 ./...
GOTOOLCHAIN=go1.27.1 go test -race -count=1 ./...
# libcore 目录
GOTOOLCHAIN=go1.27.1 go test -tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls -count=1 ./...
GOTOOLCHAIN=go1.27.1 go test -race -tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls -count=1 ./...
git diff --check
python3 scripts/check-public-content.py
```

- 新原生代码通过中性目录流程重建 AAR；后续 Android 构建使用对应新 AAR。core 与 libcore 普通/race 均通过；保留全部历史测试及生产 feature tags。
- 最终全量 JVM/Room：354 tests / 0 failures / 0 errors / 0 skipped。这是本次完整集合，包含基线 343 和新增 11 项，不与此前阶段全量数量相加。Lint 0 errors / 0 warnings / 11 原有 hints；Debug 和 instrumentation APK 构建通过。
- `BackgroundPolicyTest` 4 项覆盖来源约束、配置摘要/成功时间、到期和失败分类/退避预算、SDK 回调策略。`SubscriptionRunTest` 4 项覆盖混合结果、排队配置失效、通知异常与取消，以及同一 WorkSpec 两次尝试重叠时通知清理归属。合成四订阅模型的更新调用量分别为 1/3/1/1；这是可控业务回调计数，不是真实网络吞吐或功耗测量。
- `SubscriptionWorkConstraintsTest` 使用实际 WorkManager 2.11.2 TestDriver 和测试数据库：未满足约束时网络 Worker 执行 0 次，文档 Worker 1 次；满足网络约束后网络执行 1 次返回 retry，文档保持 1 次，两个周期任务均 ENQUEUED。WorkerFactory 用计数 Worker 替换传输边界，不冒充无网络真机测试。
- `SubscriptionDocumentTest` 合作式 Provider 验证取消信号与权限撤销；`SubscriptionRefreshRaceTest` 新增双 Room 连接验证旧配置在 begin 阶段被拒绝且不使新代次失效。既有真实 Room/SQLite 测试继续覆盖 ABA、删除、乱序、失败/取消、checkpoint 和清零顺序。
- Go 新测试覆盖 HTTP 成功、401/403/404/408/429/503 分类及失败无正文/头泄漏、结构化取消和服务器 EOF。GC 用注入时钟与收集函数：初次执行期间 1,000 个并发请求全部拒绝，窗口内不排队，窗口结束后再次执行；正常两次执行和 panic 后恢复均验证，race 通过。注入冷却为 1 分钟加速逻辑验证，生产仍为 5 分钟；不是实际 Go GC CPU/RSS 测量。
- 过程中失败保留：初次 Kotlin 编译发现 feedback lambda 非局部 return 与新增 WorkInfo 可空返回访问错误，修正后通过。文档 Provider 测试先缺 authority，再缺 openTypedAssetFile 向带 CancellationSignal 重载的转发，分别在 Android Provider 校验和文件打开处失败；补齐夹具后原取消/权限断言通过。一次构建日志被两个先后交叠的 Gradle 调用写入，混有旧编译失败；之后使用独立日志且等待上一进程退出，最终结果来自独立完整成功运行。没有删除失败测试、放宽业务断言或用旧 AAR冒充新验证。

### 隔离真机及未验证边界

USB 真机在线，`adb install -r` 更新本轮 `.debug` APK 和对应测试 APK，均收到成功回执。执行：

```text
adb shell am instrument -w -r -e class io.nekohasekai.sagernet.BackgroundSchedulingNativeTest,io.nekohasekai.sagernet.SubscriptionFetchNativeTest,io.nekohasekai.sagernet.SubscriptionPersistenceNativeTest,io.nekohasekai.sagernet.BatchConsistencyNativeTest com.vialen.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- 本批安排共 14 项：BackgroundSchedulingNativeTest 2、SubscriptionFetchNativeTest 3、SubscriptionPersistenceNativeTest 6、BatchConsistencyNativeTest 3。第一轮全部通过（逐项完成码 0，无 assumption 跳过）。包含真实 RemoteWorkManager/跨进程调度锁、旧任务取消、网络/文档来源切换、不变任务身份保留、关闭/删除清理，以及关闭正在运行的订阅后服务器在夹具清理前观察到 EOF、未提交节点/成功时间。
- 原生 HTTP 30 秒完整正文超时、超大声明拒绝、取消后子任务 join，以及真实设备 SQLite 保存/回滚、UID 快照与清流量/主线程调度历史回归通过。运行输入为合成节点和回环服务；没有启动生产 VPN、操作正式包、清用户数据、关闭 Wi-Fi 或改变显示参数。
- 手工分阶段 `WorkUpgradeNativeTest` 已适配新迁移语义：旧全局 UUID 退役、新按组任务保持配置不变时身份，保留原数据恢复和真实请求断言。本轮只编译，未做旧 APK→候选 APK 的完整分阶段迁移；本轮迁移证据来自当前隔离包的真实持久化 WorkSpec 专项。`WorkConnectedOnlyNativeTest` 适配新 Worker 输入但未执行，因为其 VPN 启停超出本轮“不改变生产 VPN”边界；相应条件由可控 JVM 路径覆盖，不能替代真实 VPN 结论。
- 未覆盖第三方远程文档 Provider 的阻塞读取/权限变化组合、实际断网导致平台约束失效（未关闭 Wi-Fi）、不同 OEM 压力及新旧 SDK 的真实回调频率、系统杀进程、跨进程调用方死亡、正式安装包设备验收；这些旧有或平台专项仍为未验证。
- 本批功能回归不能代替大列表帧耗时、CPU/能耗、GC CPU/次数、两进程内存和恢复分配的对照测量。没有报告省电比例或保证 FreeOSMemory 后 RSS 必降。

### 收尾结论

A、B 源码问题确认并优化，保留已有正确的版本/事务/统计/取消保障。已消除的无效工作有可控测试依据：不满足联网约束时网络 Worker 不启动、失败来源不带成功来源重复执行、每周期有界重试、旧配置拒绝下载/提交、UI 隐藏不主动 GC、并发/冷却内请求不累积回收任务。不变配置还避免重复远端 UPDATE 和初始时间偏移。

具备进入下一批“性能与功耗测量”的功能基础；测量批次应继续保留上述回归，并针对后台空闲、失败重试、回调压力及两个进程做同机对照。5 分钟冷却及真实平台调度效果仍需测量，不把这些策略写成已经证明的省电收益。本批不推送、不打标签、不发布；新提交只有本地验证，远端绿色仍属于原基线。

最终通知标识与调度时间保护加入后，重新完成全量 354 项 JVM/Room、Lint、Debug/测试 APK 构建，再更新两个隔离 APK，重跑同一组 14 项真机专项，仍全部通过（逐项完成码 0）。这是同组复验，不计为 28 项新增覆盖。新增时间断言确认只改变远端成功时间时，现有 WorkSpec ID 与 nextScheduleTimeMillis 均不变。

收尾再次查询基线三个工作流仍为 success，远端 main 仍为 `7953339`；没有取消或触发远端运行。完整暂存 diff 人工复核、`git diff --cached --check` 和公开内容检查通过，0 findings；未包含凭据、真实订阅、设备标识、原始日志、数据库或无关改动。

本地实现提交：`a4a636598bd2f3e2bcb014924aa5c911211c8570`（压力条件与进程内 GC 单飞/冷却），`535d7680048ab747b9943a27f5ea06c19c6706bf`（按组订阅调度、结构化结果、取消/通知/迁移及回归）。QA 单独提交；收尾仍为 main、单一工作树、2.1.3 / VERSION_CODE=101，本任务全部修改提交完整，无既有修改需要保全。

## 第四批：性能与功耗基准测量（部分交付）

开始基线 `c32f461ec1621b2e57abf27116a2b727a46a6e8b`，main，单一分支和工作树，工作区干净；2.1.3 / VERSION_CODE=101。核对 L0 为 `c3ade8f37a390ecc195ccb9704ae042c1df261c1`、L1/B0 为 `79533396032099f5c02eb2c344ad36af03c0d627`，第三批本地提交关系成立。本批不改变生产算法、版本或依赖，不推送、不打标签、不发布。

交付集中在 [benchmarks/batch4](../benchmarks/batch4/README.md)：固定协议、设备能力、性能报告、能耗报告、构建/采集/分析脚本、逐轮脱敏数据及生成数值表。原始日志、系统 trace、设备标识、测试密钥和独立构建目录均保存在仓库外，未删除用户内容。

三份历史 archive 各自完成中性路径原生 AAR 与 Release 等效 benchmark APK 构建，测试签名、同一专用包名、arm64、R8/资源压缩、非 debuggable、profileable shell=true。三份产物 SHA、上游 SHA/补丁摘要和共同观察点摘要均记录。再次用 aapt2/apksigner 核对三包 manifest、ABI 与同一测试证书；没有读取或修改正式签名。观察入口只存在于仓库外 archive，主项目生产源码未修改。

本轮实际执行：

```text
bash scripts/build-private-android.sh                 # 三份独立 archive 各自执行
./gradlew :app:assembleBenchmark                     # 三份 archive 各自执行成功
./gradlew :app:testDebugUnitTest :app:lintDebug --rerun-tasks
python3 -m unittest discover -s benchmarks/batch4/tools -p test_tools.py
python3 -m py_compile benchmarks/batch4/tools/*.py
```

本轮全量宿主 JVM/Room 354 tests / 0 failures / 0 errors / 0 skipped；Lint 0 errors / 0 warnings / 11 hints。分析和采集防护测试 6 项通过，固定源码校验接受正确归属并拒绝错误标签。真实 Android 上完成 100 节点工具预检和 L1 同 APK 3 对 A/A：6 个校准窗口、另 12 个预热窗口，全部成功，没有温度/供电/trace 完整性排除。不是重跑第三批 14 项 instrumentation，不以宿主 Room 结果替代真机专项。

A/A 滚动 P95、主进程 CPU、加载时间的配对差区间均跨零；短刷新存在顺序/缓存噪声，不能推断生产收益。下一步预检安装时 USB ADB 断开，后续只读检查无设备。已请求恢复原 USB 连接，没有切换网络或修改手机设置；按本批“环境不足时交付，不无限等待”要求收尾。正式 L/B 对照、10,000 节点、导入/JNI/GC 窗口未采；完整 Binder、消费者、后台订阅计量驱动亦未完成。原有未验证项继续保留。

硬件能力预检仅取得固定零电源轨和外部供电下的电池净量，不支持可信应用能耗归因；本轮没有完成硬件能耗对照或软件能耗估算，没有省电百分比。最后成功窗口已停止专用测量包；断连后无法再次核查设备，隔离包及原始文件保留。生产包、VPN、Wi-Fi、充电及显示设置未改。

目前没有足够证据支持下一批生产算法修改，建议先恢复测量条件并补齐正式对照与缺失驱动。此结论是部分交付，不能关闭本批性能或功耗验收。

## 首页小幅调整：纯白背景与隐藏未连接文案（2.1.4）

起点 main / `4577bc32ec2e63751a076c1ccd48c336ae1f38c4`，工作区干净。仅将主界面共享背景 drawable 的径向渐变改为纯白，并在首页只为 Connecting/Stopping 显示连接过程文字；Stopped/Idle 不再显示“未连接”。保留电源图标、连接后统计栏、无障碍状态描述、卡片及所有操作布局。版本按项目约定递增至 2.1.4 / VERSION_CODE=102。

本轮执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，构建成功；354 tests / 0 failures / 0 errors / 0 skipped，Lint 通过。没有新增仅镜像实现的测试。已安装隔离 `.debug` 包并查看真机首页空状态，确认纯白背景；非空节点的断开/连接过渡未做本轮真机状态切换验证，连接过程文字保留依据源码分支复核。没有启动 VPN、操作正式包数据、修改 Wi-Fi/显示设置。截图和原始构建日志保留仓库外。

未实施此前完整重设计、节点更多菜单或卡片结构调整。本轮只做本地提交，不推送、不发布。

## 隔离包与本地构建残留清理

按用户明确要求，卸载已连接真机的 `com.vialen.app.debug.test`、`com.vialen.app.debug`、`com.vialen.app.benchmark`，三项均返回成功，回读没有残留 Vialen 包。清理前正式 `com.vialen.app` 本就未安装，本轮未安装正式包、未改签名或生产数据。

确认无正在进行的构建，停止 Gradle daemon 后，将源码目录内 13 项可再生构建产物/缓存及 Finder 元数据移入系统垃圾篓，合计约 2013.9 MiB；未清空垃圾篓，因此不宣称释放相同磁盘空间。范围包括 app/build、根 build、Gradle/Kotlin 缓存、buildSrc 构建缓存、core/libcore 构建暂存、重复的 libcore 根 AAR 和 Python 字节码缓存。源码、QA、基准工具和构建需要的 app/libs/libcore.aar 保留，未删除归属不明内容。原始清理清单留在仓库外。

AGENTS.md 已明确后续优先原签名正式包验收，必要时才使用隔离包并在结束后清理；不得卸载正式包、清数据或用隔离包结果替代正式验收。本轮无生产代码变更、不升版、不推送；只执行公开内容检查和 Git/文件回读，不重新构建制造缓存。
