# 第四批性能报告：已完成校准，正式对照受阻

本轮不能证明第二批或第三批更快。已完成三份公平构建、设备能力验证、100 节点工具预检，以及 **L1 同一 APK 的 3 组 A/A（6 个正式校准窗口、每窗口另有两轮预热）**。正式 L0/L1 与 B0/B1 的 10 次对照尚未执行：A/A 结束后 USB 真机从 ADB 断开，后续安装返回 device not found；只读复查无设备。未开启新网络连接或模拟器替代。

354 项 JVM/Room 和 Lint 是本轮重新执行的历史功能回归，全部通过（Lint 0 error/0 warning、11 hints），不把第三批报告转抄为本轮实测。Python 分析与采集工具 6 项测试通过，另验证构建器接受正确源码归属并拒绝错误基线标签。这些均不构成性能提升证据。观察点开关的独立开销对照尚未执行，A/A 只反映当前整套工具的重复性。

## 已采集：1,000 节点 A/A 校准

下面全部为同一个 L1（7953339），同一非 debuggable、profileable、R8/资源压缩、测试签名 APK；不是优化前后两版。Full 等效 ART 编译，进程在每轮重新启动，进入真实 MainActivity 并等待 RecyclerView；稳定滚动另有当前进程内预热。外部预热用于文件/系统缓存，不能说它保留了前一进程的 Go/JVM 堆。

逐轮数值、配对绝对差/相对差及区间见 [脚本生成表](results/aa-table.md)。所有数值由逐轮 JSON 重建，未手工调整。

零超时只描述本次滚动脚本的有效帧，不证明其他滚动/设备没有卡顿。六轮温度、供电和 trace 完整性检查均未触发排除；没有因为较慢而删除任何运行。表内主进程 CPU 包含驱动与采集线程，逐轮 JSON 另列 Perfetto 主线程、RenderThread、B4Driver、B4Frames、其他线程和 :bg；不能把主进程总量称为纯生产算法 CPU。

A/A 滚动 P95、主进程 CPU 和加载时间的差异区间均跨零。短刷新窗口波动更大，密集刷新出现后段偏快；A/A 只有 3 对，不能据此分清缓存、调度与顺序效应。后续 L 对照必须使用预设交替顺序和至少 10 个有效配对，不能把几毫秒或几百分点默认当优化收益。

## 指标定义及采集修正

- Window FrameMetrics 预检存在异常 deadline（约秒级）及短窗口延迟回调，因此不用于本报告掉帧判定。原始回调保留作为设施诊断，正式汇总以 Perfetto 对齐结果为准。
- frameDurationCpu = 对应 RenderThread DrawFrame 结束 - UI Choreographer 开始，是生产帧的经过时间，不是线程实际获得 CPU 的时间。frameOverrun = max(actual frame 结束, RenderThread 结束) - expected frame 结束；CPU 实际运行量单独用 sched 切片与窗口相交计算。这与 [AndroidX FrameTimingQuery](https://android.googlesource.com/platform/frameworks/support/+/dd97834aa54671ee1f56d65fa46668b4ffeb57e8/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/perfetto/FrameTimingQuery.kt) 的定义一致。
- 使用真实 expected frame 截止时间，不统一用 16.7ms。每轮帧数和分布分别保留，不把每个帧当独立实验。Perfetto 内部 android.frames 连接 UI、RenderThread 和 FrameTimeline；无法对应的指标留空，不补零。
- Android USAP 预启动会让早期进程名仍为 usap64。预检初次按包名筛选漏掉主进程，已改由本包测量标记确定 upid，再归类线程；补充 task rename 与进程信息采样。该修正不改生产逻辑。
- 首次 Perfetto 配置路径受权限限制，改用 stdin；首次停止误用了 attach，失败证据保留，之后采用工具返回 PID、核对所属命令后 TERM 并等待落盘。失败预检不纳入 A/A。构建预检的 Kotlin DSL getByName 接收者错误已修正。未使用 suppressErrors 跳过基准约束。

## 尚未完成的矩阵

| 场景 | 本轮状态 |
|---|---|
| 1,000 节点列表 | 同版本绝对校准已采；L 对照未采，不关闭性能收益验收 |
| 10,000 节点、导入完成与通知 | 工具已构建，正式窗口未采 |
| JNI 空闲有效 tag | 三版本各自 AAR 与等价驱动已构建；断连前未执行，不填推导值为实测 |
| 实际服务/Binder、变化 tag、消费者切换 | 未实现完整测量驱动/未采集；直接 JNI 模式不能替代 |
| 有序写操作与密集刷新交错 | 当前只覆盖刷新与一次字段写入；完整写队列测量仍欠缺 |
| 订阅成功、恢复、永久错误、混合来源 | 未建立本轮完整计量窗口；第三批功能测试不代替测量 |
| GC 100 次人工请求/堆快照 | 三版本观察入口已构建，候选冷却未改；未运行，不报告实际执行次数 |
| 系统内存回调、OEM 压力/长时内存保留 | 未验证 |

因此本批为部分交付，不能关闭原有大列表性能、后台功耗、OEM 压力、系统杀进程、跨进程调用方死亡或正式包设备验收。主线程/RenderThread 的可观察 CPU 分解已经建立，但没有 Go 栈符号化或足够因果证据，不能编造具体函数热点。

## 下一轮建议

目前没有充分对照证据支持新的生产算法改动，**不建议立即继续优化代码**。先恢复同一设备条件，完成 L0/L1 的预设样本，再补实际服务/Binder 与后台/GC 的计量设施。这个顺序是测量待办，不是三项已经证实的性能缺陷。

数据：`results/aa-runs.json` 包含逐轮、预热、输入与产物摘要；`results/aa-comparison.json` 可由脚本重建；`results/artifacts.json` 固定三个 APK/AAR 的哈希。原始 trace、日志、设备标识与构建目录保留仓库外。本轮最后成功的采集已 force-stop 专用 benchmark 包；断连后不能再次回读设备状态，未操作正式包或其他应用。
