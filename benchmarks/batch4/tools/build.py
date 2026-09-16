#!/usr/bin/env python3
"""Build a fixed-source, externally staged measurement APK without reading formal signing config."""
import argparse,hashlib,json,os,pathlib,shutil,subprocess
REFS={'L0':'c3ade8f37a390ecc195ccb9704ae042c1df261c1','L1':'79533396032099f5c02eb2c344ad36af03c0d627','B1':'c32f461ec1621b2e57abf27116a2b727a46a6e8b'}
def main():
 p=argparse.ArgumentParser();p.add_argument('--source',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--label',choices=REFS,required=True);p.add_argument('--verify-only',action='store_true');a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[3];d=a.source.resolve();out=a.output.resolve();
 if out.is_relative_to(root):raise SystemExit('Artifacts and test signing key must stay outside repository')
 out.mkdir(parents=True,exist_ok=True)
 if d==root or '.git' in [x.name for x in d.iterdir()]: raise SystemExit('Use an isolated git archive, not a checkout')
 # Verify the entire pinned archive before accepting a provenance label.
 overlay_inputs={'buildSrc/src/main/kotlin/Helpers.kt','app/build.gradle.kts','app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt'}
 tree=subprocess.check_output(['git','ls-tree','-r',REFS[a.label]],cwd=root,text=True)
 for entry in tree.splitlines():
  metadata,relative=entry.split('\t',1);mode,kind,blob=metadata.split()
  if relative in overlay_inputs or kind!='blob':continue
  file=d/relative
  payload=os.readlink(file).encode() if mode=='120000' else file.read_bytes()
  actual=hashlib.sha1(b'blob '+str(len(payload)).encode()+b'\0'+payload).hexdigest()
  if actual!=blob:raise SystemExit('Source differs from pinned SHA: '+relative)
 if a.verify_only:
  print('Pinned archive source verified');return
 env=os.environ.copy();env.pop('LOCAL_PROPERTIES',None);env.pop('KEYSTORE_PASS',None);env.pop('ALIAS_PASS',None);env.pop('ALIAS_NAME',None);env.pop('nkmr_minify',None)
 def run(cmd,log,timeout=1200):
  with (out/log).open('w') as f:
   r=subprocess.run(cmd,cwd=d,env=env,stdout=f,stderr=subprocess.STDOUT,timeout=timeout)
  if r.returncode: raise SystemExit('Build step failed; inspect private '+log)
 shutil.copy2(root/'benchmarks/batch4/overlay/benchmark_runtime.go',d/'libcore/benchmark_runtime.go')
 shutil.copy2(root/'benchmarks/batch4/overlay'/('benchmark_gc_gate_b1.go' if a.label=='B1' else 'benchmark_gc_gate_legacy.go'),d/'libcore/benchmark_gc_gate.go')
 run(['bash','scripts/build-private-android.sh'],a.label+'-aar.log')
 # Restore only the known overlay inputs from the pinned source on reruns.
 for relative in ['buildSrc/src/main/kotlin/Helpers.kt','app/build.gradle.kts','app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt']:
  original=subprocess.check_output(['git','show',REFS[a.label]+':'+relative],cwd=root)
  (d/relative).write_bytes(original)
 helper=d/'buildSrc/src/main/kotlin/Helpers.kt';s=helper.read_text();start=s.index('fun Project.setupAppCommon()');end=s.index('fun Project.setupApp()',start)
 s=s[:start]+'fun Project.setupAppCommon() { setupCommon() }\n\n'+s[end:];helper.write_text(s)
 key=out/'benchmark-only.jks'
 if not key.exists():
  run([str(pathlib.Path(env['JAVA_HOME'])/'bin/keytool'),'-genkeypair','-keystore',str(key),'-storepass','benchmark-only','-keypass','benchmark-only','-alias','benchmark','-keyalg','RSA','-validity','3650','-dname','CN=Vialen Synthetic Benchmark'], 'test-signing.log',60)
 env['VIALEN_BENCH_KEYSTORE']=str(key)
 target=d/'app/src/benchmark/java/io/nekohasekai/sagernet/benchmark';target.mkdir(parents=True,exist_ok=True)
 for f in (root/'benchmarks/batch4/overlay').glob('*.kt'): shutil.copy2(f,target/f.name)
 if a.label=='L0':
  activity=target/'BenchActivity.kt';s=activity.read_text()
  bulk='''                                    override suspend fun onAdded(profiles:List<ProxyEntity>) {
                                        batches++
                                        profiles.forEach { onAdd(it) }
                                    }
'''
  if s.count(bulk)!=1 or s.count('val expectedBatches=1 // BENCH_EXPECTED_BATCHES')!=1:
   raise SystemExit('L0 observer overlay marker missing')
  s=s.replace(bulk,'').replace('val expectedBatches=1 // BENCH_EXPECTED_BATCHES','val expectedBatches=0 // BENCH_EXPECTED_BATCHES')
  activity.write_text(s)
 native=target/'BenchNative.kt';s=native.read_text()
 if a.label!='L0': s=s.replace('/* BATCH_QUERY */',', queryBatch={t-> calls++;val data=box.queryStatsBatch(t);bytes+=t.length+data.size;data}')
 native.write_text(s)
 (d/'app/src/benchmark/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><profileable android:shell="true"/><activity android:name="io.nekohasekai.sagernet.benchmark.BenchActivity" android:exported="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"/></application></manifest>''')
 db=d/'app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt';s=db.read_text();s=s.replace('.setJournalMode(JournalMode.TRUNCATE)', '.setQueryCallback({ sql, _ -> io.nekohasekai.sagernet.benchmark.BenchCounters.sql(sql) }, java.util.concurrent.Executor { it.run() })\n                .setJournalMode(JournalMode.TRUNCATE)');db.write_text(s)
 gradle=d/'app/build.gradle.kts';s=gradle.read_text();s+='''
android {
    signingConfigs.create("benchmarkOnly") {
        storeFile = file(System.getenv("VIALEN_BENCH_KEYSTORE"))
        storePassword = "benchmark-only"; keyAlias = "benchmark"; keyPassword = "benchmark-only"
    }
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        signingConfig = signingConfigs.getByName("benchmarkOnly")
        applicationIdSuffix = ".benchmark"
        isDebuggable = false; isJniDebuggable = false
        isMinifyEnabled = true; isShrinkResources = true
        matchingFallbacks += listOf("release")
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("benchmark")) {
    it.outputs.forEach { output -> output.versionCode.set(900001) }
}
''';gradle.write_text(s)
 run(['./gradlew',':app:assembleBenchmark'],a.label+'-apk-build.log')
 metadata=json.loads((d/'app/build/outputs/apk/benchmark/output-metadata.json').read_text());apk=d/'app/build/outputs/apk/benchmark'/metadata['elements'][0]['outputFile'];dest=out/(a.label+'.apk');shutil.copy2(apk,dest)
 files=[d/'app/libs/libcore.aar',dest];hashes={x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in files}
 hashes.update(source_sha=REFS[a.label],overlay_sha256=hashlib.sha256(b''.join(f.read_bytes() for f in sorted((root/'benchmarks/batch4/overlay').glob('*')))).hexdigest(),application_id=metadata['applicationId'],version_code=900001)
 (out/(a.label+'-provenance.json')).write_text(json.dumps(hashes,indent=2));print(a.label,'benchmark built',flush=True)
if __name__=='__main__': main()
