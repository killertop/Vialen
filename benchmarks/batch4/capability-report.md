# 设备与指标能力：D1

只读盘点：型号 25113PN0EC，Android 17 / API 37，arm64-v8a。当前 1220×2656、density 520、font_scale 1.0，显示 ON、renderFrameRate 约 120 Hz；设备支持可变刷新，后续用每帧实际 deadline，不采用固定 16.7ms。未修改这些参数。

盘点时电池 100%，系统报告 AC powered=true、USB powered=false、full；ADB 为 USB 数据连接。电池温度约 33.3℃，thermal status=0。thermalservice 的 cached 列表包含旧温度，不能把历史缓存峰值冒充当前温度；排除判断使用当前状态和电池读数。

| 指标 | 能力 | 实际证据/限制 |
|---|---|---|
| FrameTimeline | 可测 | 5 秒预检 trace 解析出 318 个 actual frame slices；这不是 Vialen 性能结果 |
| CPU 调度 | 可测 | 同一预检有 41,998 个 sched slices；只在分析时筛选目标进程/线程 |
| Window FrameMetrics | 仅诊断 | 预检 deadline 异常且短窗口存在延迟回调，不用于帧超时结果 |
| 主进程 CPU/PSS/Java 堆 | 已测 | 6 个 A/A 窗口已有结果；口径分别报告，不相加 |
| :bg CPU/内存 | 需目标实际运行 | 进程缺席或权限拒绝记缺失，不记零 |
| Go 堆/强制 GC 周期 | 入口已构建，未测 | 所有比较源码使用同等快照入口；断连前未运行，不伪造符号化热点 |
| 电源轨 | 目前不可用 | android.power 可注册，但 8 条轨各 6 个样本全部固定 0；不能解释为零功耗，需工作负载预检再次核对 |
| 电荷 | 代理观察 | 6 个样本固定 6,738,000 µAh，窗口内分辨率不足 |
| 电流/电压 | 代理观察 | 电流 0–6,000 µA，电压 4,448,000 µV；外部供电下是电池净量，不是应用功耗 |
| 软件能耗估算 | 仅估算，暂未采集 | 不清空全局 batterystats；不能与电源轨混称 |
| 日常深睡/无线能耗 | 外部条件阻塞 | USB/供电会改变休眠条件；不为测量擅自断电、模拟电池、切换网络或替换生产 VPN |

Perfetto 设备服务 v54.0；主机 Trace Processor v58.2（提交 add693d8b338ba9599dbcbc3e300b1ab8c000897）。初次配置文件受 SELinux 路径访问限制，改用标准输入传递，5 秒采集与本地解析成功。原始 trace 包含系统调度信息，严格保留仓库外，不上传。

主进程通过专用测量标记的 upid 识别，避免 USAP 早期进程名缺失，后台为同包 :bg；驱动主要在宿主 Python，目标内准备/同步线程名 B4Driver，帧收集 B4Frames。两线程开销应分别报告；主进程总 CPU 包含它们，不能直接称为纯生产逻辑 CPU。
