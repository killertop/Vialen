#!/usr/bin/env python3
"""Execute a predeclared, bounded sequence. No unbounded retries or best-run selection."""
import argparse,json,pathlib,hashlib,uuid
from collect import Device

def main():
 p=argparse.ArgumentParser();p.add_argument('--target-file',required=True);p.add_argument('--artifacts',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--plan',choices=['aa','L','B-gc'],required=True);a=p.parse_args()
 d=Device(a.target_file,a.output)
 plan=[]
 if a.plan=='aa':
  for i in range(3):
   for side in ['a','b']:plan.append(dict(label='L1',pair=i,side=side,counts=[1000],modes=['list']))
 elif a.plan=='L':
  for i in range(10):
   for label in (['L0','L1'] if i%2==0 else ['L1','L0']):plan.append(dict(label=label,pair=i,side=label,counts=[1000,10000],modes=['list','import','jni']))
 else:
  for i in range(10):
   for label in (['L1','B1'] if i%2==0 else ['B1','L1']):plan.append(dict(label=label,pair=i,side=label,counts=[100],modes=['gc']))
 manifest=a.output/'execution-plan.json'
 if manifest.exists():raise SystemExit('Refuse to overwrite an existing experiment')
 manifest.write_text(json.dumps(plan,indent=2));plan_hash=hashlib.sha256(manifest.read_bytes()).hexdigest()
 experiment=uuid.uuid4().hex[:8];(a.output/'experiment-id.txt').write_text(experiment)
 for step in plan:
  label=step['label'];pair=step['pair'];side=step['side'];d.install(a.artifacts/(label+'.apk'))
  for n in step['counts']:
   for mode in step['modes']:
    prefix=f'{experiment}-{a.plan}-{pair:02d}-{side}-{n}-{mode}'
    # GC starts from a fresh process; warming it would consume the production cooldown.
    warmups=0 if mode=='gc' else 2
    for iteration in range(-warmups,1):
     name=prefix+('-measure' if iteration==0 else '-warm'+str(-iteration))
     d.run(name+'-seed','seed',0 if mode=='import' else n)
     data=d.run(name,mode,n,trace=mode in ['list','import'] and iteration==0)
     data.update(label=label,pair=pair,side=side,phase='measure' if iteration==0 else 'warmup',plan_sha256=plan_hash)
     (a.output/(name+'.json')).write_text(json.dumps(data,indent=2))
     print(json.dumps({'run':name,'ok':data.get('ok'),'exclude':data['exclude_reasons']}),flush=True)
     if not data.get('ok'):raise SystemExit('Workload failed: stop protocol, retain evidence')
if __name__=='__main__':main()
