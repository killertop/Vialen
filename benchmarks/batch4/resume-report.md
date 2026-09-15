# 第四批续验：当前有效状态与列表对照

本轮生产基线为 2.1.7 / `2077e05`，不修改生产算法、不升版、不发布。本轮重新连接真机后执行；旧的 ADB 断连是历史事件，不再作为当前设备状态。

## 输入与范围

保留的 L0、L1、B1 APK 哈希与首次记录以及各自 provenance 一致，三份历史 archive 通过固定 SHA 校验，测量签名一致。Trace Processor 仍为 v58.2 / `add693d8b338ba9599dbcbc3e300b1ab8c000897`。本轮不重建或替换观察点；来源继续见 `results/artifacts.json`。

新增 `L-list` 计划只拆出原计划的列表场景，仍是 10 对交替 AB/BA、1,000/10,000 节点、每版本每规模两轮预热及一个正式窗口。新增宿主测试核对 20 次版本步骤、40 个正式窗口与 240 次种子/工作负载调用，避免场景拆分降低样本要求。导入、JNI、GC 尚未补齐的完成断言和预检不混入本次列表结论。

本轮 L1 与 L0 各完成 100 节点种子及列表预检。重新执行 L1 同 APK 的 3 对 A/A，6 个正式窗口和 12 次预热均完成、无排除配对。滚动 P95 曾出现单轮偏高，未作为异常随意剔除；本轮关键配对差区间跨零，保留实际方差，不用这 3 对小样本证明观察工具零开销。

当前电池为未外部供电状态，与首次 USB 供电不同，因此不把两轮绝对值拼成一个对照。系统电源轨仍固定零值；电池净电流有变化，但无法直接归因应用，不输出省电百分比。未更改网络、显示、充电或生产 VPN。

## 可复现命令

使用仓库外的新 `BENCH_RAW` 目录、已核对的 `BENCH_ARTIFACTS` 和 `TRACE_PROCESSOR`：

```sh
python3 benchmarks/batch4/tools/probe.py --target-file "$BENCH_RAW/target" --output "$BENCH_RAW/capability" --trace-processor "$TRACE_PROCESSOR"
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_ARTIFACTS" --output "$BENCH_RAW/aa" --plan aa
python3 benchmarks/batch4/tools/run_plan.py --target-file "$BENCH_RAW/target" --artifacts "$BENCH_ARTIFACTS" --output "$BENCH_RAW/L-list" --plan L-list
python3 -m unittest discover -s benchmarks/batch4/tools -p test_tools.py
```

原始 trace、设备资料、APK 和日志仍在仓库外；公共结果仅保留合成夹具元数据及脱敏汇总。历史记录不覆盖、不删除。

## 本轮实际结果与中止原因

| 阶段 | 完成数据 | 结论 |
|---|---|---|
| A/A | 6 正式窗口、12 预热，无排除配对 | 本轮校准完成；关键差值区间跨零，不证明零开销。 |
| 第一次 L-list | 3 正式窗口、7 预热 | L0 两种规模、L1 的 1,000 节点完成。随后 L1 的 10,000 节点预热启动成功但结果文件未在期限内生成，计划中止。 |
| 独立诊断 | L1 的 10,000 节点窗口成功 | 连接恢复后同一负载通过；不计入正式样本，不能据此确定第一次缺失的原因。 |
| 固定的一次重采 | 5 正式窗口、12 预热 | 第 0 对两种规模均完成。下一对 L1 的 1,000 节点达到 40.2℃，正式窗口标为无效，随后停止计划。 |

第一次结果缺失后，设备最初仍在线，检查时未发现测量包崩溃或 OOM；随后电脑端 ADB 两个连接记录显示 offline，重连超时。用户反馈手机仍在线后，重新核对电脑端已恢复 device。诊断轮成功。这里只记录观察顺序，**不将整个失败归因于无线连接，也不把结果文件缺失直接定为生产代码缺陷**。

重采的完整第 0 对：1,000 节点两侧起始温度差 0.9℃、10,000 节点差 0.3℃，均低于原 2℃限制；对应 trace 无 error/data loss。下一对的无效正式窗口保留在公共逐轮结果中，不能删除。两次计划分别保存，不拼接或选择较快结果；每种规模均不足预设 10 对，因此不计算或宣称版本性能提升。

- [本轮 A/A 逐轮数据](results/resume-aa-runs.json)、[配对计算](results/resume-aa-comparison.json)、[数值表](results/resume-aa-table.md)
- [第一次部分对照](results/resume-L-partial-runs.json)
- [重采部分对照（含温度排除项）](results/resume-L-recovery-partial-runs.json)

完成采集后，新增脚本防护：种子阶段失败或任一环境排除项立即停止；工作负载出现环境排除项也停止，不继续积累热状态下的窗口。8 项宿主测试通过，包含固定样本计划以及种子/工作负载温度无效时停止。此防护在本次实机中止后新增，未冒充已经实机执行；不改变原温度阈值、不放宽断言。下一轮需先固定有上限的自然冷却安排并验证连接稳定性，再采集正式计划；不能不断重跑到得到理想结果。

没有修改任何生产算法、应用版本或正式数据，没有改变手机 Wi-Fi、显示、充电及生产 VPN。已停止并卸载本轮 `com.vialen.app.benchmark`，回读仅保留正式 `com.vialen.app`。没有残留本轮隔离包。原始证据仍在仓库外。

**M1 尚未关闭；M2–M5 与平台/生命周期专项仍按当前状态清单保留。** 本轮只完成待办整理、测量预检/校准及部分对照，不是第四批完整验收。
