#!/usr/bin/env python3
"""Create dedicated git archives; never reset, stash or edit the source checkout."""
import argparse,hashlib,json,pathlib,subprocess
from build import REFS

def main():
 p=argparse.ArgumentParser();p.add_argument('--repo',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
 repo=a.repo.resolve();out=a.output.resolve()
 if out.is_relative_to(repo):raise SystemExit('Raw build sources must be outside repository')
 out.mkdir(parents=True,exist_ok=False)
 for name,sha in REFS.items():
  dest=out/name;dest.mkdir();archive=subprocess.Popen(['git','archive',sha],cwd=repo,stdout=subprocess.PIPE)
  extraction=subprocess.run(['tar','-xf','-','-C',str(dest)],stdin=archive.stdout);archive.stdout.close()
  if extraction.returncode or archive.wait():raise SystemExit('Archive failed; preserve partial output for inspection')
 sources={}
 for name in ['libneko','sing-box','sing-tun']:
  path=repo.parent/name
  head=subprocess.check_output(['git','-C',str(path),'rev-parse','HEAD'],text=True).strip()
  patch=subprocess.check_output(['git','-C',str(path),'diff','HEAD'])
  status=subprocess.check_output(['git','-C',str(path),'status','--porcelain'],text=True)
  sources[name]={'head':head,'patch_sha256':hashlib.sha256(patch).hexdigest(),'status':status}
  (out/name).symlink_to(path,target_is_directory=True)
 (out/'upstreams.json').write_text(json.dumps(sources,indent=2));(out/'sources.json').write_text(json.dumps(REFS,indent=2))
 print('Prepared independent sources; upstream modifications are recorded, not changed')
if __name__=='__main__':main()
