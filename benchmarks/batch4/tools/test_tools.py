import json,pathlib,tempfile,unittest
import contextlib,io,runpy
from unittest.mock import patch,MagicMock
from compare import estimate,get
from analyze import percentile
from build import REFS
from collect import Device,workload_failures

class AnalysisContracts(unittest.TestCase):
 def test_constant_pair_has_zero_difference_without_fake_zero_baseline_percentage(self):
  x=estimate([(0,0)]*10)
  self.assertEqual(x['n'],10);self.assertEqual(x['mean_difference'],0)
  self.assertEqual(x['ci95'],[0,0]);self.assertIsNone(x['relative_percent'])
 def test_known_paired_shift_and_interval(self):
  x=estimate([(i,i+2) for i in range(1,11)])
  self.assertEqual(x['mean_difference'],2);self.assertEqual(x['ci95'],[2,2])
 def test_missing_metrics_are_missing_not_zero(self):
  self.assertIsNone(get({'a':None},'a'));self.assertIsNone(get({},'missing'))
  self.assertIsNone(percentile([],.95));self.assertEqual(get({'a':0},'a'),0)
 def test_percentile_uses_run_values_without_pseudoreplication(self):
  self.assertEqual(percentile([10,20,30],.5),20)
  self.assertEqual(estimate([(1,2),(2,4)])['n'],2)
 def test_raw_evidence_cannot_be_written_inside_repository(self):
  with self.assertRaisesRegex(ValueError,'outside repository'):
   Device('unused-private-target',pathlib.Path(__file__).resolve().parent)
 def test_comparisons_pin_three_distinct_production_sources(self):
  self.assertEqual(len(set(REFS.values())),3)
  self.assertTrue(all(len(s)==40 for s in REFS.values()))
 def test_list_only_plan_preserves_pairs_sizes_and_warmups(self):
  with tempfile.TemporaryDirectory() as temp:
   root=pathlib.Path(temp);device=MagicMock()
   device.run.return_value={'ok':True,'exclude_reasons':[]}
   args=['run_plan.py','--target-file','unused','--artifacts',temp,'--output',temp,'--plan','L-list']
   with patch('collect.Device',return_value=device),patch('sys.argv',args),contextlib.redirect_stdout(io.StringIO()):
    runpy.run_path(str(pathlib.Path(__file__).with_name('run_plan.py')),run_name='__main__')
   plan=json.loads((root/'execution-plan.json').read_text())
   self.assertEqual(len(plan),20)
   for pair in range(10):
    steps=plan[pair*2:pair*2+2]
    self.assertEqual([x['label'] for x in steps],['L0','L1'] if pair%2==0 else ['L1','L0'])
    self.assertTrue(all(x['pair']==pair and x['counts']==[1000,10000] and x['modes']==['list'] for x in steps))
   calls=device.run.call_args_list
   self.assertEqual(len(calls),240) # seed plus workload, three iterations per size/version/pair
   measured=[c for c in calls if c.kwargs.get('trace')]
   self.assertEqual(len(measured),40)
   self.assertTrue(all(c.args[1]=='list' for c in measured))
 def test_import_requires_committed_selection_and_exact_notifications(self):
  valid={'committed_rows':100,'selection_valid':True,'notification_batches':1,'notification_rows':100}
  self.assertEqual(workload_failures('import',100,valid),[])
  for key,value in [('committed_rows',99),('selection_valid',False),('notification_batches',0),('notification_rows',99)]:
   with self.subTest(key=key):
    bad=dict(valid);bad[key]=value
    self.assertTrue(workload_failures('import',100,bad))
  old=dict(valid,provenance={'source_sha':REFS['L0']},notification_batches=0)
  self.assertEqual(workload_failures('import',100,old),[])
  self.assertTrue(workload_failures('import',100,dict(old,notification_batches=1)))
 def test_invalid_seed_or_environment_stops_without_more_work(self):
  for stage in ['seed','workload']:
   with self.subTest(stage=stage),tempfile.TemporaryDirectory() as temp:
    device=MagicMock()
    invalid={'ok':True,'exclude_reasons':['battery temperature >=40C']}
    device.run.side_effect=([invalid] if stage=='seed' else [{'ok':True,'exclude_reasons':[]},invalid])
    args=['run_plan.py','--target-file','unused','--artifacts',temp,'--output',temp,'--plan','L-list']
    with patch('collect.Device',return_value=device),patch('sys.argv',args),contextlib.redirect_stdout(io.StringIO()):
     with self.assertRaisesRegex(SystemExit,'invalid: stop protocol'):
      runpy.run_path(str(pathlib.Path(__file__).with_name('run_plan.py')),run_name='__main__')
    self.assertEqual(device.run.call_count,1 if stage=='seed' else 2)
    self.assertEqual(device.install.call_count,1)

class CoolingContracts(unittest.TestCase):
 def device(self,root,states):
  d=MagicMock();d.root=pathlib.Path(root);d.environment.side_effect=states;return d
 def state(self,temp=360,usb='false'):
  return dict(temperature_tenths_c=str(temp),thermal_status=0,ac='false',usb=usb,status='3')
 def clock(self):
  now=[0]
  def sleep(seconds):now[0]+=seconds
  return lambda:now[0],sleep
 def test_hot_device_waits_then_requires_two_ready_checks(self):
  from cooling import CoolingBudget
  with tempfile.TemporaryDirectory() as root,contextlib.redirect_stdout(io.StringIO()):
   d=self.device(root,[self.state(390),self.state(),self.state()]);clock,sleep=self.clock()
   gate=CoolingBudget(d,clock=clock,sleep=sleep);gate.wait('sample')
   self.assertEqual(clock(),32);self.assertEqual(gate.remaining,1768)
   self.assertEqual(len(json.loads((pathlib.Path(root)/'sample-cooling.json').read_text())['checks']),3)
 def test_wait_is_bounded_and_evidence_survives_failure(self):
  from cooling import CoolingBudget
  with tempfile.TemporaryDirectory() as root,contextlib.redirect_stdout(io.StringIO()):
   d=self.device(root,[self.state(390)]*3);clock,sleep=self.clock()
   with self.assertRaisesRegex(RuntimeError,'budget exhausted'):
    CoolingBudget(d,max_wait=60,clock=clock,sleep=sleep).wait('sample')
   self.assertEqual(clock(),60);self.assertTrue((pathlib.Path(root)/'sample-cooling.json').exists())
 def test_missing_state_and_power_change_fail_closed(self):
  from cooling import CoolingBudget
  for states in [[{}],[self.state(),self.state(usb='true')]]:
   with tempfile.TemporaryDirectory() as root,contextlib.redirect_stdout(io.StringIO()):
    d=self.device(root,states);clock,sleep=self.clock()
    with self.assertRaises(RuntimeError):CoolingBudget(d,clock=clock,sleep=sleep).wait('sample')
 def test_total_budget_applies_across_gates(self):
  from cooling import CoolingBudget
  with tempfile.TemporaryDirectory() as root,contextlib.redirect_stdout(io.StringIO()):
   d=self.device(root,[self.state()]*3);clock,sleep=self.clock();gate=CoolingBudget(d,total_wait=2,clock=clock,sleep=sleep)
   gate.wait('first')
   with self.assertRaisesRegex(RuntimeError,'budget exhausted'):gate.wait('second')
   self.assertEqual(clock(),2)

if __name__=='__main__':unittest.main()
