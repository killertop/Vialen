> 后续进展和当前限制见 [续验记录](resume-report.md)；以下保留首次测量过程。

# 第四批测量工具与交付

本次是**部分测量交付**：完成设备能力盘点、三份独立测量 APK/AAR、100 节点预检及同一 L1 构建的 6 个 A/A 校准窗口。USB 真机随后断开，尚无 L0/L1 或 B0/B1 正式对照；不能得出性能提升或省电结论。

- [固定协议](comparison-plan.md)
- [设备能力](capability-report.md)
- [性能结果与未覆盖矩阵](performance-report.md)
- [能耗限制](energy-report.md)
- [逐轮数据](results/aa-runs.json)、[生成数值表](results/aa-table.md)、[产物来源](results/artifacts.json)

## 准备与构建

需要仓库锁定的 Java 25、Go 1.27.1、Android SDK/NDK 和现有上游源码。`JAVA_HOME`、SDK 与工具 PATH 沿用既有本机环境。`BENCH_RAW` 必须指定仓库外的新目录；原始 trace、设备信息、日志、测试密钥和 APK 均不得提交。

```sh
export BENCH_RAW="$(mktemp -d -t vialen-batch4)"
python3 benchmarks/batch4/tools/prepare.py --repo "$PWD" --output "$BENCH_RAW/versions"
python3 benchmarks/batch4/tools/build.py --source "$BENCH_RAW/versions/L0" --output "$BENCH_RAW/artifacts" --label L0
python3 benchmarks/batch4/tools/build.py --source "$BENCH_RAW/versions/L1" --output "$BENCH_RAW/artifacts" --label L1
python3 benchmarks/batch4/tools/build.py --source "$BENCH_RAW/versions/B1" --output "$BENCH_RAW/artifacts" --label B1
```

每份独立构建原生 AAR，不共用候选 AAR。构建器只接受固定 SHA 的仓库外 archive；上游现有补丁只读记录，不自动覆盖。正式签名读取代码在 archive 中移除，测试密钥仅生成在产物目录。测量 Kotlin/Go/SQL 观察代码也只叠加到 archive，主项目 Release 不包含入口。原生 tags 沿用生产构建脚本。

构建、采集、分析脚本使用显式超时，失败不伪造数据。失败目录保留供检查，不自动删除。对照前应审核 `versions/upstreams.json` 与本轮记录的上游版本和补丁哈希；当前准备器记录它们，但不会自动检出或应用旧补丁。

## 真机采集

只用已经连接、获授权的物理设备。将选定 ADB 标识写入仓库外私有文件 `BENCH_RAW/target`，不要在公共结果中使用它。工具仅控制 `com.vialen.app.benchmark`，不启动生产 VPN、不清正式数据；不得通过网络/显示/充电设置变化来续接设备。

```sh
python3 benchmarks/batch4/tools/probe.py --target-file "$BENCH_RAW/target" --output "$BENCH_RAW/capability" --trace-processor "$TRACE_PROCESSOR"
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_RAW/artifacts" --output "$BENCH_RAW/aa" --plan aa
# 每个目录只能启动一次，先确认预检和环境。
# 先仅运行已具备完成断言的列表入口；仍为 10 对、两种节点规模。
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_RAW/artifacts" --output "$BENCH_RAW/L-list" --plan L-list
# 以下完整入口尚需补齐文末列出的导入/JNI/GC 预检：
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_RAW/artifacts" --output "$BENCH_RAW/L" --plan L
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_RAW/artifacts" --output "$BENCH_RAW/B-gc" --plan B-gc
```

Trace Processor 必须固定并回读版本，本轮为 v58.2 / add693d8b338ba9599dbcbc3e300b1ab8c000897。A/A 是 3 对同版本重复，L/B 是 10 对 AB/BA；安装后每次 Full 等效 ART 编译。GC 使用新进程，不预热强制回收以免消耗真实冷却。清理只 force-stop 本工具的专用包；原始数据、隔离包不自动卸载或删除。

自动排除仅实现温度、thermal status、供电变化、工作完成、摘要/trace 完整性。续验后增加种子失败和运行环境排除即停止的防护，见续验记录。前台遮挡、通知和显示变化仍需人工核对并记录；当前脚本没有自动补足无效轮，需遵守协议最多两轮的预先安排补采，不能无限运行到显著。A/A 之后补充了输出目录限制、运行 ID 唯一性和启动失败清理，未重跑设备；这些宿主防护改动不改变已构建 APK 的观察点。

目前 `jni` 仅直接真实原生接口的空闲采样；`B-gc` 仅人工调用入口。它们未实测，不能替代完整服务/Binder、变化 tag、消费者、真实系统 GC 或后台订阅窗口。后者驱动仍未实现。不要把脚本能启动称为全部矩阵已覆盖。

## 重建汇总

```sh
python3 benchmarks/batch4/tools/analyze.py --input "$BENCH_RAW/aa" --output "$BENCH_RAW/aa-runs.json" --trace-processor "$TRACE_PROCESSOR"
python3 benchmarks/batch4/tools/compare.py --input benchmarks/batch4/results/aa-runs.json --output "$BENCH_RAW/aa-comparison.json" --aa
python3 benchmarks/batch4/tools/render.py --runs benchmarks/batch4/results/aa-runs.json --comparison "$BENCH_RAW/aa-comparison.json" --output "$BENCH_RAW/aa-table.md"
python3 -m unittest discover -s benchmarks/batch4/tools -p test_tools.py
python3 scripts/check-public-content.py
```

`analyze.py` 从仓库外逐轮原始 JSON/Perfetto 重建去标识化结果，输出发布前仍须人工审核和公开内容扫描。比较脚本以运行/配对为单位 bootstrap，缺失值保留为空，不把帧数扩充成独立样本。`render.py` 重建数值表，禁止手工改数。

原始系统 trace 不提交，因此其他读者可从公开逐轮结果复算汇总，不能仅凭公开文件重新验证完整系统 trace。原始证据由本地保留，未上传公共服务。

测量限制：本轮没有观察点关闭/开启的独立开销对照，A/A 只估计当前整套工具的重复性。未执行的 import/JNI/GC 驱动仍需小规模真机预检；当前 import 会记录 selection_valid，但不能仅依靠顶层 ok 忽略选择失败，正式对照前须补齐这项硬断言和通知计数。当前 GC 两秒窗口也不能单独证明所有异步回收已结束或长期内存代价。


## 自然冷却计划（提示修复后补充）

在 `run_plan.py` 上显式加 `--cooling`；相同实验的 A/A 和 L-list 都使用同一开关，不能混用有/无冷却结果。门控位于每轮 seed 前以及 seed 完成后的测量前，均在计时窗口外。就绪需连续两次温度不高于 37℃、thermal status 为 0；就绪复核间隔 2 秒，未就绪时每 30 秒读取一次。每个门控最多 300 秒，全计划累计等待最多 1,800 秒；耗尽、状态缺失或冷却中供电变化即终止并保留记录。不改系统设置、不用风扇/外部降温干预、不反复重跑无效样本。原运行中 40℃、热限频及配对温差规则继续有效。

```sh
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_ARTIFACTS" --output "$BENCH_RAW/aa-cooled" --plan aa --cooling
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_ARTIFACTS" --output "$BENCH_RAW/L-cooled" --plan L-list --cooling
```

冷却策略单独写入 `cooling-policy.json`，每次门控的读取值与耗时只写入仓库外原始目录。它是一项预先固定的采集条件，不是性能优化；此前测量数据不能追溯改标为已采用此策略。
