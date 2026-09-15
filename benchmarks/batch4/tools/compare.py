#!/usr/bin/env python3
"""Paired run-level estimates; frame samples are never independent replicates."""
import argparse,json,pathlib,random,statistics
from analyze import percentile

def get(row,path):
 try:
  value=row
  for part in path.split('.'):value=value[part]
  return float(value) if value is not None else None
 except (KeyError,TypeError,ValueError):return None

def estimate(pairs):
 d=[b-a for a,b in pairs];rng=random.Random(413)
 boot=[statistics.mean(rng.choices(d,k=len(d))) for _ in range(5000)]
 old=statistics.mean(a for a,b in pairs);new=statistics.mean(b for a,b in pairs)
 return dict(n=len(d),baseline_mean=old,candidate_mean=new,mean_difference=statistics.mean(d),relative_percent=(new-old)/old*100 if abs(old)>1e-6 else None,ci95=[percentile(boot,.025),percentile(boot,.975)])
def main():
 p=argparse.ArgumentParser();p.add_argument('--input',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--baseline',default='L0');p.add_argument('--candidate',default='L1');p.add_argument('--aa',action='store_true');a=p.parse_args()
 rows=[x for x in json.loads(a.input.read_text()) if x.get('phase')=='measure'];groups={};exclusions=[]
 for x in rows:
  label=x.get('side') if a.aa else x.get('label')
  groups.setdefault((x['mode'],x['nodes'],x['pair']),{})[label]=x
 left,right=('a','b') if a.aa else (a.baseline,a.candidate)
 metrics=['load_ms','scroll.elapsed_ms','refresh.elapsed_ms','burst.elapsed_ms','import.elapsed_ms','jni.elapsed_ms','gc.elapsed_ms']
 for w in ['scroll','refresh','burst','import']:
  metrics += [w+'.process_cpu_ms',w+'.driver_cpu_ms',w+'.sql.profile_reads',w+'.sql.queries',w+'.sql.writes',w+'.memory_after.pss_kb']
 # Percentile keys contain dots, so extract frame metrics into flat aliases first.
 for x in rows:
  for w,v in x.get('perfetto',{}).get('windows',{}).items():
   for stat in ['frame_count','overrun_fraction']:x[w+'_'+stat]=v[stat]
   for q in ['0.5','0.95','0.99']:x[w+'_cpu_p'+q[2:]]=v['frame_duration_cpu_ms'][q]
 metrics += [w+'_'+s for w in ['scroll','refresh','burst','import'] for s in ['frame_count','overrun_fraction','cpu_p5','cpu_p95','cpu_p99']]
 data={}
 for (mode,n,pair),by in groups.items():
  if left not in by or right not in by:exclusions.append([mode,n,pair,'missing pair']);continue
  l,r=by[left],by[right];reasons=l.get('exclude_reasons',[])+r.get('exclude_reasons',[])
  if l.get('fixture_hash')!=r.get('fixture_hash'):reasons.append('fixture mismatch')
  for x in [l,r]:
   if x.get('perfetto',{}).get('trace_stats'):reasons.append('trace error/data loss')
  try:
   if abs(int(l['environment_before']['temperature_tenths_c'])-int(r['environment_before']['temperature_tenths_c']))>20:reasons.append('pair temperature difference >2C')
  except (KeyError,TypeError):reasons.append('environment missing')
  if reasons:exclusions.append([mode,n,pair,sorted(set(reasons))]);continue
  for metric in metrics:
   lv,rv=get(l,metric),get(r,metric)
   if lv is not None and rv is not None:data.setdefault((mode,n,metric),[]).append((lv,rv))
 result={'comparison':[left,right],'estimates':[dict(mode=k[0],nodes=k[1],metric=k[2],**estimate(v)) for k,v in data.items()],'excluded_pairs':exclusions}
 a.output.write_text(json.dumps(result,indent=2));print('estimates',len(data),'excluded pairs',len(exclusions))
if __name__=='__main__':main()
