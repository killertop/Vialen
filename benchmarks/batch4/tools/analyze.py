#!/usr/bin/env python3
"""Rebuild de-identified per-run summaries from private JSON and Perfetto, never upload traces."""
import argparse,csv,hashlib,io,json,pathlib,statistics,subprocess,random
PACKAGE='com.vialen.app.benchmark'
def percentile(values,p):
 if not values:return None
 v=sorted(values);i=(len(v)-1)*p;k=int(i);return v[k]+(v[min(k+1,len(v)-1)]-v[k])*(i-k)
def query(tool,trace,sql):
 r=subprocess.run([str(tool),str(trace),'-Q',sql],capture_output=True,text=True,timeout=60)
 if r.returncode:raise RuntimeError('Trace SQL failed: '+r.stderr[-600:])
 return list(csv.DictReader(io.StringIO(r.stdout)))
def trace_metrics(tool,trace):
 prefix="INCLUDE PERFETTO MODULE android.frames.timeline; CREATE PERFETTO TABLE b4_windows AS SELECT name,ts,dur FROM slice WHERE name GLOB 'B4_*' AND dur>0; CREATE PERFETTO TABLE b4_targets AS SELECT DISTINCT t.upid FROM slice s JOIN process_track t ON s.track_id=t.id WHERE s.name GLOB 'B4_*';"
 frames=query(tool,trace,prefix+f'''SELECT w.name, (rt.ts+rt.dur-ui.ts)/1e6 AS cpu_ms,
 CASE WHEN a.id IS NOT NULL AND e.id IS NOT NULL THEN (max(a.ts+a.dur,rt.ts+rt.dur)-e.ts-e.dur)/1e6 END AS overrun_ms
 FROM android_frames f JOIN slice ui ON ui.id=f.do_frame_id JOIN slice rt ON rt.id=f.draw_frame_id
 LEFT JOIN actual_frame_timeline_slice a ON a.id=f.actual_frame_timeline_id
 LEFT JOIN expected_frame_timeline_slice e ON e.id=f.expected_frame_timeline_id
 JOIN b4_windows w ON ui.ts>=w.ts AND ui.ts<w.ts+w.dur
 WHERE f.upid IN (SELECT upid FROM b4_targets) AND ui.dur>0 AND rt.dur>0''')
 cpu=query(tool,trace,prefix+f'''SELECT w.name,CASE WHEN p.upid IN (SELECT upid FROM b4_targets) THEN 'main' ELSE 'bg' END AS process,CASE WHEN t.tid=p.pid THEN 'main_thread' WHEN t.name IN ('RenderThread','B4Driver','B4Frames') THEN t.name ELSE 'other' END AS thread,
 sum(min(s.ts+s.dur,w.ts+w.dur)-max(s.ts,w.ts))/1e6 AS cpu_ms
 FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid)
 JOIN b4_windows w ON s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts
 WHERE (p.upid IN (SELECT upid FROM b4_targets) OR p.name='{PACKAGE}:bg') AND s.dur>0
 GROUP BY w.name,process,thread''')
 stats=query(tool,trace,"SELECT name,value FROM stats WHERE severity IN ('error','data_loss') AND value>0")
 out={}
 for name in sorted({r['name'] for r in frames+cpu}):
  f=[r for r in frames if r['name']==name];c=[float(r['cpu_ms']) for r in f]
  o=[float(r['overrun_ms']) for r in f if r['overrun_ms']!='[NULL]']
  out[name.removeprefix('B4_')]={'frame_count':len(f),'frame_overrun_count':len(o),'frame_duration_cpu_ms':{str(p):percentile(c,p) for p in [.5,.95,.99]},'frame_overrun_ms':{str(p):percentile(o,p) for p in [.5,.95,.99]},'overrun_fraction':sum(x>0 for x in o)/len(o) if o else None,'thread_cpu':[{k:v for k,v in r.items() if k!='name'} for r in cpu if r['name']==name]}
 return {'windows':out,'trace_stats':stats,'trace_sha256':hashlib.sha256(trace.read_bytes()).hexdigest()}
def main():
 p=argparse.ArgumentParser();p.add_argument('--input',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--trace-processor',type=pathlib.Path,required=True);a=p.parse_args();rows=[]
 for f in sorted(a.input.glob('*.json')):
  data=json.loads(f.read_text())
  if 'run' not in data:continue
  trace=f.with_suffix('.pftrace')
  clean={k:v for k,v in data.items() if k not in ['load_frames']}
  for name in ['scroll','refresh','burst','import']:
   if name in clean:
    clean[name]=dict(clean[name]);frames=clean[name].pop('frames',[])
    clean[name]['window_callback_frames']=len(frames);clean[name]['window_dropped']=sum(x[3] for x in frames)
  if trace.exists():clean['perfetto']=trace_metrics(a.trace_processor,trace)
  rows.append(clean)
 a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(rows,indent=2));print('summarized runs',len(rows))
if __name__=='__main__':main()
