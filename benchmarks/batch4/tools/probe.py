#!/usr/bin/env python3
"""Read-only capability probe. No battery simulation, charging writes, network changes or history resets."""
import argparse,pathlib,json,hashlib,csv,io
from collect import Device
from analyze import query

def main():
 p=argparse.ArgumentParser();p.add_argument('--target-file',required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--trace-processor',type=pathlib.Path,required=True);a=p.parse_args()
 repo=pathlib.Path(__file__).resolve().parents[3]
 if a.output.resolve().is_relative_to(repo):raise SystemExit('Raw probe evidence must stay outside repository')
 d=Device(a.target_file,a.output)
 summary={'device':'D1','environment':d.environment()}
 for name,key in [('model','ro.product.model'),('api','ro.build.version.sdk'),('android','ro.build.version.release'),('abi','ro.product.cpu.abilist')]:summary[name]=d.adb(['shell','getprop '+key]).strip()
 for name,cmd in [('display','wm size; wm density; settings get system font_scale; dumpsys display'),('power','dumpsys battery'),('thermal','dumpsys thermalservice'),('sources','perfetto --query')]:d.adb(['shell',cmd],30,name+'.txt')
 config='''buffers { size_kb: 8192 fill_policy: RING_BUFFER }
duration_ms: 5000
data_sources { config { name: "android.power" android_power_config { battery_poll_ms: 1000 battery_counters: BATTERY_COUNTER_CHARGE battery_counters: BATTERY_COUNTER_CURRENT battery_counters: BATTERY_COUNTER_VOLTAGE collect_power_rails: true } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.ftrace" ftrace_config { ftrace_events: "sched/sched_switch" } } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
'''
 d.adb(['shell','perfetto --txt -c - -o /data/misc/perfetto-traces/vialen-b4-probe.pftrace'],20,'probe-capture.txt',config.encode())
 trace=a.output/'probe.pftrace';d.adb(['pull','/data/misc/perfetto-traces/vialen-b4-probe.pftrace',str(trace)],30,'probe-pull.txt')
 summary['counters']=query(a.trace_processor,trace,'SELECT name,count(*) AS samples,min(value) AS minimum,max(value) AS maximum FROM counter JOIN counter_track ON track_id=counter_track.id GROUP BY name')
 summary['trace_sha256']=hashlib.sha256(trace.read_bytes()).hexdigest()
 (a.output/'capability-summary.json').write_text(json.dumps(summary,indent=2));print('Probe retained privately; review before publishing a summary')
if __name__=='__main__':main()
