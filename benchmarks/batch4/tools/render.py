#!/usr/bin/env python3
"""Rebuild the report's numerical section from sanitized run-level data."""
import argparse,json,pathlib

def main():
 p=argparse.ArgumentParser();p.add_argument('--runs',type=pathlib.Path,required=True);p.add_argument('--comparison',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
 rows=[r for r in json.loads(a.runs.read_text()) if r.get('phase')=='measure']
 lines=['# A/A 校准数值（脚本生成）','','同一 L1 APK；不代表优化前后差异。', '', '| 轮次 | 列表可用 ms | 滚动帧数 | frameDurationCpu P95 ms | 截止超时比例 | 主进程 CPU ms |','|---|---:|---:|---:|---:|---:|']
 for r in sorted(rows,key=lambda r:(r['pair'],r['side'])):
  w=r['perfetto']['windows']['scroll']
  lines.append(f"| {r['pair']:02d}-{r['side']} | {r['load_ms']:.2f} | {w['frame_count']} | {w['frame_duration_cpu_ms']['0.95']:.3f} | {w['overrun_fraction']*100:.2f}% | {r['scroll']['process_cpu_ms']:.0f} |")
 lines+=['','配对差为 b − a；每次运行是一份样本，bootstrap 区间只反映本次小样本，不证明可泛化收益。','','| 指标 | 配对数 | a 均值 | b 均值 | 绝对差 | 相对差 | 95% 区间 |','|---|---:|---:|---:|---:|---:|---|']
 for e in json.loads(a.comparison.read_text())['estimates']:
  if e['metric'] not in ['load_ms','scroll_cpu_p95','scroll.process_cpu_ms','refresh.elapsed_ms','burst.elapsed_ms']:continue
  relative='不可计算' if e['relative_percent'] is None else f"{e['relative_percent']:.3f}%"
  lines.append(f"| {e['metric']} (ms) | {e['n']} | {e['baseline_mean']:.4f} | {e['candidate_mean']:.4f} | {e['mean_difference']:+.4f} | {relative} | {e['ci95'][0]:+.4f} ～ {e['ci95'][1]:+.4f} |")
 a.output.write_text('\n'.join(lines)+'\n')
if __name__=='__main__':main()
