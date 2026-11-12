"""Check endpoint arithmetic and alignment independently of cohort outcomes."""
import numpy as np
from clinical_experiments import compare,interval_metrics,st_level,subject

def test_matching_does_not_double_count_extra_beat():
    metrics,mapping=compare(np.array([250,500,750]),np.array([249,251,505,1000]))
    assert metrics['tp']==2 and metrics['fp']==2 and metrics['fn']==1
    assert len(set(mapping[mapping>=0]))==2

def test_no_detections_is_complete_failure_not_exclusion():
    metrics,mapping=compare(np.array([250,500]),np.array([],dtype=int))
    assert metrics['fn']==2 and metrics['f1']==0 and np.all(mapping==-1)

def test_rr_intervals_and_rmssd_have_correct_units():
    values=interval_metrics(np.array([0,250,525,775]))
    assert values['mean_nn_ms']==np.mean([1000,1100,1000])
    assert values['rmssd_ms']==100
    assert np.isclose(values['sdnn_ms'],np.std([1000,1100,1000],ddof=1))

def test_normal_intervals_do_not_bridge_ectopic_or_invalid_pairs():
    values=interval_metrics(np.array([0,250,500,750,1000,1250]),np.array([True,True,False,True,True,True]))
    assert values['n_intervals']==3 and values['rmssd_ms']==0

def test_st_measurement_reference_and_offset_invariance():
    x=np.zeros(100);x[58:63]=.2;x[35:40]=.05;p={'onset':40,'st':60}
    assert np.isclose(st_level(x,p),.15)
    assert np.isclose(st_level(x+8,p),.15)

def test_repeated_patient_records_share_cluster():
    assert subject('mitdb','201')==subject('mitdb','202')
    assert subject('edb','e0123')==subject('edb','e0126')
    assert subject('fantasia','f1o01')!=subject('fantasia','f2o01')

def test_one_nonzero_pair_cannot_be_significant():
    from summarize_clinical import signed_rank_test
    assert signed_rank_test([0,0,1])[0]==1.

def test_signed_rank_exact_two_sided_tail():
    from summarize_clinical import signed_rank_test
    assert signed_rank_test([1,2,3,4])[0]==.125

def test_signed_rank_ties_and_balanced_signs():
    from summarize_clinical import signed_rank_test
    assert signed_rank_test([1,-1,0])[0]==1.
