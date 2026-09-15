import json,pathlib,tempfile,unittest
from compare import estimate,get
from analyze import percentile
from build import REFS
from collect import Device

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
if __name__=='__main__':unittest.main()
