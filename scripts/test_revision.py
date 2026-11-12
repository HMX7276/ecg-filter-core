import numpy as np
import pytest
from scipy import signal, ndimage
from java_support import compile_java, java_filter

@pytest.fixture(scope='session',autouse=True)
def build(): compile_java()

@pytest.mark.parametrize('order',[2,4,6,8])
@pytest.mark.parametrize('cutoff',[0.01,0.05,0.1,0.5,1.0])
def test_sos_against_scipy(tmp_path,order,cutoff):
    t=np.arange(10000)/250
    x=1000*np.sin(2*np.pi*1.3*t)+200*np.sin(2*np.pi*0.12*t)+np.random.default_rng(73).normal(0,30,len(t))
    sos=signal.butter(order,cutoff,fs=250,btype='highpass',output='sos')
    p=tmp_path/'coeff.f64'; sos.astype('<f8').tofile(p)
    actual=java_filter(x,tmp_path,mode='custom',extra=p)
    expected=signal.sosfiltfilt(sos,x)
    np.testing.assert_allclose(actual,expected,atol=2e-5,rtol=1e-8)

@pytest.mark.parametrize('config,hp,lp,order',[
    ('CONFIG_DEFAULT',.05,35,4),('CONFIG_BASELINE_HEAVY',.1,35,4),
    ('CONFIG_MUSCLE_HEAVY',.05,30,4),('CONFIG_CONSERVATIVE',.1,30,2)])
def test_config_pipeline(tmp_path,config,hp,lp,order):
    x=np.random.default_rng(5).normal(0,500,2500)
    y=x
    for cutoff,btype in [(hp,'highpass'),(lp,'lowpass')]: y=signal.sosfiltfilt(signal.butter(order,cutoff,btype=btype,fs=250,output='sos'),y)
    np.testing.assert_allclose(java_filter(x,tmp_path,config=config),y,atol=2e-6,rtol=1e-8)

def test_bwa_matches_independent_median(tmp_path):
    x=np.random.default_rng(12).normal(0,40,5000)+300*np.sin(np.arange(5000)*.002)
    actual=java_filter(x,tmp_path,mode='features').reshape(-1,2)[:,0]
    expected=[]
    for start in range(0,2501,1250):
        baseline=ndimage.median_filter(ndimage.median_filter(x[start:start+2500],51,mode='nearest'),151,mode='nearest')
        expected.append(np.std(baseline))
    np.testing.assert_allclose(actual,expected,atol=1e-10)

@pytest.mark.parametrize('length',[1,15,1250,2499,2500,2501,3750,4123,10000])
def test_stream_chunk_invariance_and_length(tmp_path,length):
    x=np.random.default_rng(5).normal(0,500,length)
    a=java_filter(x,tmp_path/'a',mode='stream',extra='37')
    b=java_filter(x,tmp_path/'b',mode='stream',extra='3000')
    assert len(a)==length and np.isfinite(a).all()
    np.testing.assert_array_equal(a,b)

def test_constant_rejection(tmp_path):
    y=java_filter(np.ones(5000)*1000,tmp_path,mode='stream')
    assert np.max(np.abs(y))<1e-5

def test_msqi_dc_offset_invariance(tmp_path):
    x=np.random.default_rng(3).normal(0,300,2500)
    a=java_filter(x,tmp_path/'a',mode='features')
    b=java_filter(x+10000,tmp_path/'b',mode='features')
    np.testing.assert_allclose(a,b,atol=1e-9,rtol=1e-10)
