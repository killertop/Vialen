#!/usr/bin/env python3
"""Only controls the dedicated benchmark package. Raw ADB output stays outside the repository."""
import argparse,hashlib,json,pathlib,re,subprocess,time,shlex
PACKAGE='com.vialen.app.benchmark'
class Device:
 def __init__(self,target,root):
  self.root=pathlib.Path(root).resolve()
  if self.root.is_relative_to(pathlib.Path(__file__).resolve().parents[3]): raise ValueError('Raw evidence must be outside repository')
  self.serial=pathlib.Path(target).read_text().strip();self.root.mkdir(parents=True,exist_ok=True)
  if not self.serial or '\n' in self.serial: raise ValueError('invalid private target file')
 def adb(self,args,timeout=30,raw=None,stdin=None):
  try:r=subprocess.run(['adb','-s',self.serial,*args],capture_output=True,timeout=timeout,input=stdin)
  except subprocess.TimeoutExpired: raise RuntimeError('ADB operation timed out') from None
  if raw: (self.root/raw).write_bytes(r.stdout+r.stderr)
  if r.returncode: raise RuntimeError('ADB operation failed; inspect private evidence')
  return r.stdout.decode(errors='replace')
 def environment(self):
  b=self.adb(['shell','dumpsys battery']);t=self.adb(['shell','dumpsys thermalservice'])
  def val(key):
   m=re.search(r'^\s*'+re.escape(key)+r':\s*(.*)$',b,re.M);return m.group(1).strip() if m else None
  status=re.search(r'Thermal Status:\s*(\d+)',t)
  return {'level':val('level'),'temperature_tenths_c':val('temperature'),'ac':val('AC powered'),'usb':val('USB powered'),'status':val('status'),'thermal_status':int(status.group(1)) if status else None}
 def install(self,apk):
  p=pathlib.Path(apk);self.metadata=json.loads(p.with_name(p.stem+'-provenance.json').read_text());(self.root/'installed.json').write_text(json.dumps(self.metadata));self.adb(['install','-r',str(p)],180,'install.log')
  text=self.adb(['shell','cmd package compile -f -m speed '+PACKAGE],120,'compile.log')
  if 'Success' not in text: raise RuntimeError('ART full compilation not confirmed')
  self.adb(['shell','dumpsys package '+PACKAGE],30,'package.txt')
 def trace_start(self,run):
  config=f'''buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
unique_session_name: "b4_{run}"
duration_ms: 150000
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" }} }}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{ ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking" ftrace_events: "task/task_newtask" ftrace_events: "task/task_rename" atrace_categories: "gfx" atrace_categories: "view" atrace_apps: "{PACKAGE}" }} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }} }} }}
'''
  started=self.adb(['shell',f'perfetto --background-wait --txt -c - -o /data/misc/perfetto-traces/b4_{run}.pftrace'],15,run+'-trace-start.txt',config.encode())
  pids=re.findall(r'^([0-9]+)$',started,re.M)
  if len(pids)!=1:raise RuntimeError('Trace PID not confirmed')
  self.trace_pid=int(pids[0])
 def trace_stop(self,run):
  pid=self.trace_pid
  command=f'''case "$(tr '\\000' ' ' < /proc/{pid}/cmdline)" in *b4_{run}.pftrace*) kill -TERM {pid};; *) exit 7;; esac; i=0; while [ -d /proc/{pid} ] && [ "$i" -lt 50 ]; do sleep 0.1; i=$((i+1)); done'''
  self.adb(['shell',command],20,run+'-trace-stop.txt')
  self.adb(['pull',f'/data/misc/perfetto-traces/b4_{run}.pftrace',str(self.root/(run+'.pftrace'))],30,run+'-trace-pull.txt')
 def run(self,run,mode,count,trace=False):
  if not re.fullmatch('[A-Za-z0-9_-]{1,100}',run) or mode not in ['seed','list','import','jni','gc']: raise ValueError('invalid run')
  if not 0<=count<=10000: raise ValueError('invalid count')
  exists=self.adb(['shell',f'[ -e /sdcard/Android/data/{PACKAGE}/files/b4/{run}.json ] && echo exists || echo absent'])
  if exists.strip()!='absent':raise RuntimeError('Run ID already exists; use a new experiment ID')
  self.adb(['shell','am force-stop '+PACKAGE])
  before=self.environment();start=time.time()
  if trace:self.trace_start(run)
  path=f'/sdcard/Android/data/{PACKAGE}/files/b4/{run}.json'
  cmd=f'i=0; while [ ! -f {shlex.quote(path)} ] && [ "$i" -lt 140 ]; do sleep 1; i=$((i+1)); done; cat {shlex.quote(path)}'
  try:
   self.adb(['shell',f'am start -W -n {PACKAGE}/io.nekohasekai.sagernet.benchmark.BenchActivity --es run {run} --es mode {mode} --ei count {count}'],30,run+'-launch.txt')
   text=self.adb(['shell',cmd],150,run+'-result.txt');data=json.loads(text)
  finally:
   try:
    if trace:self.trace_stop(run)
   finally:self.adb(['shell','am force-stop '+PACKAGE])
  data.update(environment_before=before,environment_after=self.environment(),host_elapsed_s=time.time()-start,device='D1')
  data['provenance']=json.loads((self.root/'installed.json').read_text()) if (self.root/'installed.json').exists() else {}
  reasons=[]
  if any(data['environment_before'][k]!=data['environment_after'][k] for k in ['ac','usb','status']):reasons.append('power state changed')
  for e in [data['environment_before'],data['environment_after']]:
   if e['thermal_status'] is None: reasons.append('thermal state missing')
   elif e['thermal_status']>=2: reasons.append('thermal throttling')
   if e['temperature_tenths_c'] is None: reasons.append('temperature missing')
   elif int(e['temperature_tenths_c'])>=400: reasons.append('battery temperature >=40C')
  if not data.get('ok'):reasons.append('workload failed')
  if data.get('debuggable'):reasons.append('debuggable target')
  data['exclude_reasons']=sorted(set(reasons));data['raw_sha256']=hashlib.sha256(text.encode()).hexdigest()
  (self.root/(run+'.json')).write_text(json.dumps(data,indent=2));return data

def main():
 p=argparse.ArgumentParser();p.add_argument('--target-file',required=True);p.add_argument('--output',required=True);p.add_argument('--apk');p.add_argument('--run',required=True);p.add_argument('--mode',required=True);p.add_argument('--count',type=int,required=True);p.add_argument('--trace',action='store_true');a=p.parse_args()
 d=Device(a.target_file,a.output)
 try:
  if a.apk:d.install(a.apk)
  data=d.run(a.run,a.mode,a.count,a.trace);print(json.dumps({'run':a.run,'ok':data.get('ok'),'excluded':data['exclude_reasons']}))
 except Exception as e:raise SystemExit(type(e).__name__+': '+str(e)) from None
if __name__=='__main__': main()
