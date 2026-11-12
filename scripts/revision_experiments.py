"""Reproducible revision experiments. All amplitudes uV; no clinical ground-truth claim.
Run download_data.py first. Save record-level CSVs and figures under results/revision.
"""
import json
import sys
import platform
import subprocess
import warnings
from pathlib import Path
import numpy as np
import pandas as pd
import scipy
from scipy import signal, ndimage, stats
import pywt
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from java_support import ROOT, BUILD, compile_java, executable, run_manifest

FS=250; W=2500; H=1250; SEED=20261112
CENTERED='--uncentered' not in sys.argv
OUT=ROOT/('results/revision' if CENTERED else 'results/ablation_uncentered'); CACHE=OUT/'work'
CONFIGS=[(.05,35,4),(.1,35,4),(.05,30,4),(.1,30,2)]
NAMES=['CONFIG_DEFAULT','CONFIG_BASELINE_HEAVY','CONFIG_MUSCLE_HEAVY','CONFIG_CONSERVATIVE']
SOS=[(signal.butter(n,hp,fs=FS,btype='highpass',output='sos'),signal.butter(n,lp,fs=FS,output='sos')) for hp,lp,n in CONFIGS]

def filt(x,c=0,causal=False):
    fn=signal.sosfilt if causal else signal.sosfiltfilt
    for sos in SOS[c]: x=fn(sos,x)
    return x

def rmse(a,b): return float(np.sqrt(np.mean((a-b)**2)))
def corr(a,b): return float(np.corrcoef(a,b)[0,1])
def snr(reference,y): return float(10*np.log10(np.mean(reference**2)/np.mean((y-reference)**2)))
def scale_noise(x,n,snr_db):
    n=n-np.mean(n)
    return n*np.sqrt(np.mean((x-np.mean(x))**2)/(np.mean(n*n)*10**(snr_db/10)))

def features(x):
    b=ndimage.median_filter(ndimage.median_filter(x,51,mode='nearest'),151,mode='nearest')
    if CENTERED: x=x-np.mean(x)
    hp=signal.butter(2,35,fs=FS,btype='highpass')
    m=np.sum(signal.lfilter(*hp,x)**2)/max(np.sum(x*x),1e-12)
    return np.std(b),np.clip(m,0,1)

def threshold(f): return np.median(f,axis=0)+3*1.4826*np.median(abs(f-np.median(f,axis=0)),axis=0)
def targets(f,t):
    b=f[:,0]>t[0]; m=f[:,1]>t[1]
    return np.where(b,np.where(m,3,1),np.where(m,2,0))
def choose(f,t=None,hysteresis=3):
    if t is None: t=threshold(f[:11]); t[1]=max(t[1],.05)
    decision=targets(f,t); active=np.zeros(len(f),int); current=0; pending=0; count=0
    for i in range(11,len(f)):
        target=decision[i]
        if target==current: count=0; pending=current
        else:
            count=count+1 if pending==target else 1; pending=target
            if count>=hysteresis: current=target; count=0
        active[i]=current
    return active,decision,t

def ola(x,configs):
    weight=.5-.5*np.cos(2*np.pi*(np.arange(W)+.5)/W)
    total=np.zeros(len(x)); denom=np.zeros(len(x))
    for i,start in enumerate(range(0,len(x)-W+1,H)):
        y=filt(x[start:start+W],int(configs[i]))
        total[start:start+W]+=weight*y; denom[start:start+W]+=weight
    if np.any(denom==0): raise ValueError('Experiment length must align to full windows')
    return total/denom

def bootstrap_mean(x,repeats=5000):
    x=np.asarray(x); rng=np.random.default_rng(SEED)
    values=np.mean(x[rng.integers(0,len(x),(repeats,len(x)))],axis=1)
    return tuple(np.quantile(values,[.025,.975]))

def subject_id(record):
    return 'mitdb_subject_201_202' if record in ['mitdb_201','mitdb_202'] else record

def signed_test(x):
    x=np.asarray(x); nonzero=x[x!=0]
    return 1.0 if len(nonzero)==0 else float(stats.wilcoxon(x,alternative='two-sided',method='auto').pvalue)

def savefig(fig,name):
    fig.savefig(OUT/(name+'.png'),dpi=300,bbox_inches='tight')
    fig.savefig(OUT/(name+'.pdf'),bbox_inches='tight'); plt.close(fig)

def numerical(records):
    jobs=[]; entries=[]
    for key,x in records.items():
        src=CACHE/(key+'.f64'); x[:W].astype('<f8').tofile(src)
        for c,name in enumerate(NAMES):
            for mode in ['sos','direct']:
                dst=CACHE/f'{key}_{c}_{mode}.f64'; jobs.append([mode,src,dst,name]); entries.append((key,c,mode,dst))
    run_manifest(jobs,CACHE)
    rows=[]
    for key,c,mode,dst in entries:
        x=records[key][:W]; actual=np.fromfile(dst,dtype='<f8')
        if mode=='sos': reference=filt(x,c)
        else:
            reference=x.copy(); hp,lp,n=CONFIGS[c]
            for fc,btype in [(hp,'highpass'),(lp,'lowpass')]: reference=signal.filtfilt(*signal.butter(n,fc,fs=FS,btype=btype),reference)
        rows.append(dict(record=key,config=NAMES[c],realization=mode,rmse_uv=rmse(actual,reference),max_abs_uv=float(np.max(abs(actual-reference))),correlation=corr(actual,reference)))
    frame=pd.DataFrame(rows); frame.to_csv(OUT/'numerical_per_record.csv',index=False)
    fig,ax=plt.subplots(figsize=(7,3.7))
    for mode,label in [('direct','Direct form vs SciPy filtfilt'),('sos','SOS vs SciPy sosfiltfilt')]:
        v=frame[(frame.config==NAMES[0])&(frame.realization==mode)]
        ax.plot(np.arange(len(v)),np.maximum(v.rmse_uv,1e-15),'o',ms=3,label=label)
    ax.set(yscale='log',xlabel='Record index (48 MIT-BIH, then 8 Fantasia)',ylabel='RMSE (uV)')
    ax.legend(fontsize=8); fig.tight_layout(); savefig(fig,'fig1_numerical_equivalence')
    return frame

def stability(records):
    x=records['mitdb_100'][:W]; rows=[]; jobs=[]
    for order in [2,4,6,8]:
        for cutoff in [.01,.05,.1,.5,1.]:
            sos=signal.butter(order,cutoff,fs=FS,btype='highpass',output='sos')
            cp=CACHE/f'coeff_{order}_{cutoff}.f64'; sos.astype('<f8').tofile(cp)
            dst=CACHE/f'stability_{order}_{cutoff}.f64'
            jobs.append(['custom',CACHE/'mitdb_100.f64',dst,NAMES[0],cp])
    run_manifest(jobs,CACHE)
    for order in [2,4,6,8]:
        for cutoff in [.01,.05,.1,.5,1.]:
            actual=np.fromfile(CACHE/f'stability_{order}_{cutoff}.f64',dtype='<f8')
            sos=signal.butter(order,cutoff,fs=FS,btype='highpass',output='sos'); reference=signal.sosfiltfilt(sos,x)
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                direct=signal.filtfilt(*signal.butter(order,cutoff,fs=FS,btype='highpass'),x)
            rows.append(dict(order=order,cutoff_hz=cutoff,sos_vs_scipy_rmse_uv=rmse(actual,reference),sos_peak_ratio=np.max(abs(actual))/np.max(abs(x)),direct_peak_ratio=np.max(abs(direct))/np.max(abs(x)),sos_change_from_raw_uv=rmse(actual,x),direct_change_from_raw_uv=rmse(direct,x)))
    frame=pd.DataFrame(rows); frame.to_csv(OUT/'stability.csv',index=False)
    fig,axes=plt.subplots(1,2,figsize=(8,3.4))
    for ax,col,title in zip(axes,['direct_peak_ratio','sos_peak_ratio'],['Direct form (SciPy)','SOS (Java)']):
        v=frame.pivot(index='order',columns='cutoff_hz',values=col)
        im=ax.imshow(np.log10(v),aspect='auto',vmin=-1,vmax=max(1,np.log10(frame.direct_peak_ratio.max())))
        ax.set_xticks(range(5),['.01','.05','.1','.5','1']); ax.set_yticks(range(4),['2','4','6','8'])
        ax.set(title=title,xlabel='High-pass cutoff (Hz)',ylabel='Order')
        for i in range(4):
            for j in range(5): ax.text(j,i,f'{v.iloc[i,j]:.1e}',ha='center',va='center',fontsize=6,color='white')
    fig.colorbar(im,ax=axes.tolist(),label='log10 output/input peak ratio',shrink=.8)
    savefig(fig,'fig3_stability')
    return frame

def make_stream(x,noise):
    y=x.copy(); t=np.arange(len(x))/FS
    for a,b,kind,db in [(120,180,'bw',0),(300,360,'powerline',0),(420,480,'bw',-6)]:
        sl=slice(a*FS,b*FS); n=np.sin(2*np.pi*50*t[sl]) if kind=='powerline' else noise[kind][sl]
        y[sl]+=scale_noise(x[sl],n,db)
    return y

def streaming(records,noise):
    variants=['raw_start','bw_start_6dB','bw_start_0dB','ma_start_0dB']
    jobs=[]; signals={}
    for key,x in records.items():
        base=make_stream(x,noise)
        for variant in variants:
            y=base.copy()
            if variant!='raw_start':
                kind='ma' if variant.startswith('ma') else 'bw'; db=6 if '6dB' in variant else 0
                y[:60*FS]+=scale_noise(x[:60*FS],noise[kind][:60*FS],db)
            source=CACHE/f'{key}_{variant}_input.f64'; y.astype('<f8').tofile(source)
            dst=CACHE/f'{key}_{variant}_features.f64'; jobs.append(['features' if CENTERED else 'features_raw',source,dst])
            signals[key,variant]=y
        jobs.append(['adaptive' if CENTERED else 'adaptive_raw',CACHE/f'{key}_raw_start_input.f64',CACHE/f'{key}_adaptive_java.f64'])
        jobs.append(['stream',CACHE/f'{key}_raw_start_input.f64',CACHE/f'{key}_fixed_java.f64'])
    run_manifest(jobs,CACHE)
    feature_map={(key,var):np.fromfile(CACHE/f'{key}_{var}_features.f64',dtype='<f8').reshape(-1,2) for key in records for var in variants}
    # Disjoint explicit record split; MIT-BIH records 201/202 belong to one subject.
    mit=[k for k in records if k.startswith('mitdb')]
    train=mit[:24]
    if ('mitdb_201' in train)!=('mitdb_202' in train):
        train=[k for k in train if k not in ['mitdb_201','mitdb_202']]
    pooled=np.concatenate([feature_map[k,'raw_start'][:11] for k in train])
    transfer=np.quantile(pooled,.95,axis=0); transfer[1]=max(.05,transfer[1])
    (OUT/'split.json').write_text(json.dumps({'training_records':train,'test_records':[k for k in records if k not in train], 'global_95th_percentile_thresholds':transfer.tolist(), 'note':'201 and 202 kept in same partition; training records excluded from transfer summaries'},indent=2))
    starts=np.arange(119)*5; ends=starts+10
    bw=((starts>=120)&(ends<=180))|((starts>=420)&(ends<=480))
    pl=(starts>=300)&(ends<=360)
    anynoise=((starts<180)&(ends>120))|((starts<360)&(ends>300))|((starts<480)&(ends>420))
    clean=(starts>=60)&~anynoise
    rows=[]; window_rows=[]; boundary=[]
    for key,x in records.items():
        for variant in variants:
            f=feature_map[key,variant]; active,decision,t=choose(f)
            choices=[('session',active,decision,t)]
            if variant=='raw_start' and key not in train:
                for factor in [.5,1.,2.]:
                    tx=transfer*factor; a,d,_=choose(f,tx); choices.append((f'transfer_x{factor:g}',a,d,tx))
            for policy,a,d,tx in choices:
                rows.append(dict(record=key,database=key.split('_')[0],initial_condition=variant,policy=policy,
                    bwa_threshold_uv=tx[0],msqi_threshold=tx[1],bw_index_recall=np.mean(np.isin(d[bw],[1,3])),
                    bw_active_recall=np.mean(np.isin(a[bw],[1,3])),powerline_active_recall=np.mean(np.isin(a[pl],[2,3])),
                    clean_false_alarm_rate=np.mean(a[clean]!=0),switches=int(np.sum(a[1:]!=a[:-1])),
                    switches_without_hysteresis=int(np.sum(d[12:]!=d[11:-1])),clean_windows=int(clean.sum()),bw_windows=int(bw.sum())))
            if variant!='raw_start': continue
            y=signals[key,variant]; expected=ola(y,active); java=np.fromfile(CACHE/f'{key}_adaptive_java.f64',dtype='<f8')
            if rmse(java,expected)>2e-5: raise AssertionError(f'Java/Python adaptive mismatch {key}: {rmse(java,expected)}')
            fixed=np.fromfile(CACHE/f'{key}_fixed_java.f64',dtype='<f8'); full=filt(y)
            independent=np.concatenate([filt(y[s:s+W]) for s in range(0,len(y),W)])
            seam=np.zeros(len(y),bool)
            for loc in range(W,len(y)-W,H): seam[loc-25:loc+25]=True
            interior=(np.arange(len(y))>=W)&(np.arange(len(y))<len(y)-W)
            for method,output in [('independent_10s',independent),('overlap_add',fixed)]:
                error=output-full
                boundary.append(dict(record=key,method=method,seam_rmse_uv=rmse(output[seam],full[seam]),interior_rmse_uv=rmse(output[interior],full[interior]),max_abs_uv=float(np.max(abs(error))),p95_abs_uv=float(np.quantile(abs(error),.95))))
            for i,s in enumerate(starts):
                sl=slice(int(s*FS),int((s+10)*FS))
                window_rows.append(dict(record=key,start_s=s,bwa_uv=f[i,0],msqi=f[i,1],bwa_threshold_uv=t[0],msqi_threshold=t[1],candidate=int(decision[i]),active=int(active[i]),host_reference_rmse_uv=rmse(java[sl],x[sl]),fixed_host_reference_rmse_uv=rmse(fixed[sl],x[sl]),contains_injected_noise=bool(anynoise[i])))
    frame=pd.DataFrame(rows); frame.to_csv(OUT/'streaming_per_record.csv',index=False)
    windows=pd.DataFrame(window_rows); windows.to_csv(OUT/'streaming_per_window.csv',index=False)
    pd.DataFrame(boundary).to_csv(OUT/'boundary_per_record.csv',index=False)
    fig,axes=plt.subplots(3,1,figsize=(8,7),sharex=True)
    for ax,key in zip(axes,['mitdb_100','mitdb_207','fantasia_f1o01']):
        w=windows[windows.record==key]
        ax.step(w.start_s+10,w.active,where='post',label='Active configuration')
        ax.step(w.start_s+10,w.candidate,where='post',alpha=.45,ls='--',label='Candidate')
        for a,b in [(120,180),(300,360),(420,480)]: ax.axvspan(a,b,color='#d98f45',alpha=.2)
        ax.set(title=key,yticks=[0,1,2,3],ylabel='Configuration')
    axes[0].legend(fontsize=8); axes[-1].set_xlabel('Decision availability time (s)')
    fig.tight_layout(); savefig(fig,'fig5_streaming')
    fig,axes=plt.subplots(1,2,figsize=(8,3.8))
    v=frame[frame.policy=='session']
    for ax,col,title in zip(axes,['bw_active_recall','clean_false_alarm_rate'],['Baseline episode recall','Clean-window false-alarm rate']):
        ax.boxplot([v[v.initial_condition==z][col] for z in variants],tick_labels=['Raw','BW +6','BW 0','MA 0'],showfliers=True)
        ax.set(title=title,xlabel='Noise in initial 60 s (dB)',ylim=(-.05,1.05))
    fig.tight_layout(); savefig(fig,'fig4_calibration_sensitivity')
    return frame,transfer

def wavelet(x,bayes=False):
    coeff=pywt.wavedec(x,'db4',level=5); sigma=np.median(abs(coeff[-1]))/.67448975
    for i in range(1,len(coeff)):
        thr=sigma**2/max(np.sqrt(max(np.mean(coeff[i]**2)-sigma**2,0)),1e-12) if bayes else sigma*np.sqrt(2*np.log(len(x)))
        coeff[i]=pywt.threshold(coeff[i],thr,mode='soft')
    return pywt.waverec(coeff,'db4')[:len(x)]

def denoising(records,noise,transfer):
    rows=[]
    for key,x in records.items():
        raw=x[60*FS:70*FS].copy(); raw-=np.mean(raw)
        pre=signal.sosfiltfilt(signal.butter(4,[.5,35],fs=FS,btype='bandpass',output='sos'),raw)
        for refname,reference in [('unfiltered_host',raw),('prefiltered_0.5_35',pre)]:
            for kind,n in noise.items():
                for db in [-6,-3,0,3,6,12]:
                    mixture=reference+scale_noise(reference,n[60*FS:70*FS],db)
                    f=np.array([features(mixture)]); c=int(targets(f,transfer)[0])
                    methods={'no_filter':mixture,'moving_average_5':np.convolve(mixture,np.ones(5)/5,'same'),
                        'wavelet_universal':wavelet(mixture),'wavelet_bayes':wavelet(mixture,True),
                        'causal_sos':filt(mixture,causal=True),'fixed_sos':filt(mixture),
                        'fixed_0.10_sos':filt(mixture,1),'adaptive_sos':filt(mixture,c)}
                    input_snr=snr(reference,mixture)
                    for method,y in methods.items(): rows.append(dict(record=key,reference=refname,noise=kind,input_snr_db=db,method=method,snr_improvement_db=snr(reference,y)-input_snr,rmse_uv=rmse(y,reference),correlation=corr(y,reference)))
        print('denoising',key,flush=True)
    frame=pd.DataFrame(rows); frame.to_csv(OUT/'denoising_per_record.csv',index=False)
    split=json.loads((OUT/'split.json').read_text()); test=frame[frame.record.isin(split['test_records'])]
    test.groupby(['reference','noise','method'])[['snr_improvement_db','rmse_uv','correlation']].mean().to_csv(OUT/'denoising_test_summary.csv')
    comparisons=[]
    for (ref,noise_type),group in test.groupby(['reference','noise']):
        pivot=group.groupby(['record','method']).snr_improvement_db.mean().unstack()
        delta=pivot.adaptive_sos-pivot.fixed_sos
        delta=delta.groupby([subject_id(k) for k in delta.index]).mean()
        lo,hi=bootstrap_mean(delta)
        comparisons.append(dict(reference=ref,noise=noise_type,n_subjects=len(delta),mean_snr_gain_db=delta.mean(),ci_low=lo,ci_high=hi,wilcoxon_p=signed_test(delta),shapiro_p=stats.shapiro(delta).pvalue if len(delta)>2 else np.nan))
    comparison=pd.DataFrame(comparisons); p=comparison.wilcoxon_p.to_numpy(); order=np.argsort(p)
    adjusted=np.empty(len(p)); adjusted[order]=np.minimum(1,np.maximum.accumulate(p[order]*(len(p)-np.arange(len(p)))))
    comparison['holm_p']=adjusted; comparison.to_csv(OUT/'paired_statistics.csv',index=False)
    return comparison

def synthetic_st():
    """Analytical PQRST templates; ST measured at known J+80 ms relative to known PR.
    Shape simulation only: not an ischemia/clinical test and not a learned reference.
    """
    rng=np.random.default_rng(SEED); rows=[]
    for case in range(30):
        hr=rng.uniform(50,100); rr=60/hr; st=float(rng.choice([-200,-100,0,100,200])); t=np.arange(120*FS)/FS
        phase=((t-.5)%rr); r=.25; qrs=rng.uniform(.07,.12); j=r+qrs/2
        x=90*np.exp(-.5*((phase-(r-.16))/.025)**2)-150*np.exp(-.5*((phase-(r-.026))/.009)**2)
        x+=rng.uniform(800,1400)*np.exp(-.5*((phase-r)/.012)**2)-250*np.exp(-.5*((phase-(r+.03))/.012)**2)
        x+=280*np.exp(-.5*((phase-(j+.23))/.05)**2)
        plateau=(np.clip((phase-j)/.012,0,1)*np.clip((j+.18-phase)/.02,0,1)); x+=st*plateau
        r_times=np.arange(.5+r,120,rr); j_times=r_times+qrs/2
        keep=(j_times>40)&(j_times<100); j_times=j_times[keep]; r_times=r_times[keep]
        st_idx=np.rint((j_times+.08)*FS).astype(int); pr_idx=np.rint((r_times-.07)*FS).astype(int)
        reference=x[st_idx]-x[pr_idx]
        choices={'zero_phase_0.05':filt(x), 'zero_phase_0.10':filt(x,1),'causal_0.05':filt(x,causal=True),
                 'causal_twice_0.05':filt(filt(x,causal=True),causal=True),
                 'overlap_0.05':ola(x,np.zeros((len(x)-W)//H+1,int))}
        for method,y in choices.items():
            delta=(y[st_idx]-y[pr_idx])-reference
            rows.append(dict(case=case,heart_rate=hr,st_plateau_uv=st,qrs_s=qrs,method=method,beats=len(st_idx),st_mae_uv=np.mean(abs(delta)),st_signed_bias_uv=np.mean(delta),st_max_abs_uv=np.max(abs(delta))))
    frame=pd.DataFrame(rows); frame.to_csv(OUT/'synthetic_st.csv',index=False)
    fig,ax=plt.subplots(figsize=(7,3.8)); methods=frame.method.unique()
    ax.boxplot([frame[frame.method==m].st_mae_uv for m in methods],tick_labels=['Zero .05','Zero .10','Causal .05','Causal x2','Overlap .05'])
    ax.set(ylabel='ST absolute error vs analytical signal (uV)',xlabel='Filtering method')
    fig.tight_layout(); savefig(fig,'fig6_synthetic_st')
    return frame

def main():
    OUT.mkdir(parents=True,exist_ok=True); CACHE.mkdir(exist_ok=True); compile_java()
    manifest=json.loads((ROOT/'data/manifest.json').read_text())
    records={}; noise={}
    for item in manifest['records']:
        key=item['database']+'_'+item['record']; x=np.load(ROOT/'data/processed'/(key+'.npz'))['x']
        if item['database']=='nstdb': noise[item['record']]=x
        else: records[key]=x
    versions={'python':platform.python_version(),'numpy':np.__version__,'scipy':scipy.__version__,'platform':platform.platform(),
        'java':subprocess.run([executable('java'),'-version'],capture_output=True,text=True).stderr,
        'seed':SEED,'records':len(records),'centered_msqi':CENTERED,'analysis_unit':'record descriptives; subject-level paired inference combines MIT-BIH 201/202',
        'date':'2026-11-12','source_commit':'bffd1ff0d79a5a0e4736a6f29e380c147298748c'}
    (OUT/'environment.json').write_text(json.dumps(versions,indent=2))
    plt.rcParams.update({'font.size':9,'axes.spines.top':False,'axes.spines.right':False})
    print('numerical',flush=True); num=numerical(records)
    print('stability',flush=True); stable=stability(records)
    print('streaming',flush=True); stream,transfer=streaming(records,noise)
    print('denoising',flush=True); paired=denoising(records,noise,transfer)
    print('synthetic ST',flush=True); st=synthetic_st()
    summary={'numerical_max_rmse_uv':num.groupby('realization').rmse_uv.max().to_dict(),
        'streaming':stream[stream.policy=='session'].groupby(['database','initial_condition'])[['bw_active_recall','powerline_active_recall','clean_false_alarm_rate']].mean().reset_index().to_dict('records'),
        'synthetic_st_mean_mae_uv':st.groupby('method').st_mae_uv.mean().to_dict()}
    (OUT/'summary.json').write_text(json.dumps(summary,indent=2)); print(json.dumps(summary,indent=2),flush=True)

if __name__=='__main__': main()
