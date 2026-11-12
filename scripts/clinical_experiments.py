"""Annotation-based retrospective extension; actual Java filtering, fixed endpoints.

See docs/retrospective_protocol.json. Raw waveforms and temporary outputs are
never treated as clinical truth. Cached endpoint records include failure counts.
"""
import argparse, hashlib, json, math, os, re, warnings
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
import numpy as np
import pandas as pd
import wfdb
from wfdb import processing
from scipy.signal import resample_poly
from java_support import ROOT,compile_java,run_manifest
from download_data import DATA

FS=250
OUT=ROOT/'results/clinical'
WORK=OUT/'work'
METHODS=['unfiltered','whole_sos_default','fixed_overlap_default','adaptive_overlap']
BEATS=set('NLRaVFJASEj/QBefnr')
EDB_GROUPS=[['e0118','e0119','e0120','e0121','e0122'],['e0123','e0124','e0125','e0126'],['e0129','e0133'],['e0136','e0139'],['e0147','e0148'],['e0154','e0155'],['e0162','e0163']]

class MissingSignalError(ValueError):
    def __init__(self,details):
        super().__init__('Nonfinite native waveform');self.details=details

def subject(db,name):
    if db=='mitdb' and name in ['201','202']:return 'mitdb_201_202'
    if db=='edb':
        for group in EDB_GROUPS:
            if name in group:return 'edb_'+group[0]
    return db+'_'+name

def native(db,name,seconds=None,all_leads=False):
    path=DATA/'raw'/db/name;h=wfdb.rdheader(str(path))
    if all_leads:channels=list(range(h.n_sig))
    else:channels=[next((i for i,s in enumerate(h.sig_name) if s.upper()=='MLII'),next((i for i,s in enumerate(h.sig_name) if 'ECG' in s.upper()),0))]
    n=h.sig_len if seconds is None else min(h.sig_len,round(seconds*h.fs))
    r=wfdb.rdrecord(str(path),sampto=n,channels=channels)
    factor=np.array([{'mV':1000.,'uV':1.,'µV':1.}[u] for u in r.units])
    x=r.p_signal*factor
    bad=np.flatnonzero(~np.isfinite(x).all(axis=1))
    if len(bad):
        runs=np.split(bad,np.where(np.diff(bad)>1)[0]+1)
        raise MissingSignalError(dict(database=db,record=name,subject=subject(db,name),reason='nonfinite_native_ECG_no_imputation',missing_samples=len(bad),native_fs=h.fs,scanned_seconds=n/h.fs,longest_gap_seconds=max(map(len,runs))/h.fs,first_missing_s=bad[0]/h.fs,last_missing_s=bad[-1]/h.fs))
    g=math.gcd(FS,round(h.fs));x=resample_poly(x,FS//g,round(h.fs)//g,axis=0)
    if not np.isfinite(x).all():raise ValueError('Nonfinite native waveform')
    a=wfdb.rdann(str(path),'ecg' if db=='fantasia' else 'atr',sampto=n)
    samples=np.rint(a.sample*FS/h.fs).astype(int)
    return x,samples,a,r.sig_name,h.fs

def variants(x,tag):
    # Each worker reuses its own scratch files; only clinical endpoint tables persist.
    folder=WORK/f'worker_{os.getpid()}';folder.mkdir(parents=True,exist_ok=True)
    source=folder/'input.f64';np.asarray(x,dtype='<f8').tofile(source)
    jobs=[]
    for method,mode in [('whole_sos_default','sos'),('fixed_overlap_default','stream'),('adaptive_overlap','adaptive')]:
        jobs.append([mode,source,folder/(method+'.f64'),'CONFIG_DEFAULT',1250])
    run_manifest(jobs,folder)
    result={'unfiltered':x}
    for method in METHODS[1:]:
        y=np.fromfile(folder/(method+'.f64'),dtype='<f8')
        if len(y)!=len(x) or not np.isfinite(y).all():raise ValueError('Invalid Java output: '+tag+' '+method)
        result[method]=y
    return result

def detect(x):return processing.xqrs_detect(np.asarray(x)/1000.,FS,learn=True,verbose=False)

def compare(ref,test,width=38):
    ref=np.asarray(ref,dtype=int);test=np.asarray(test,dtype=int)
    if not len(ref) or not len(test):
        return {'tp':0,'fp':len(test),'fn':len(ref),'sensitivity':0. if len(ref) else np.nan,'ppv':0. if len(test) else np.nan,'f1':0.,'localization_median_ms':np.nan,'localization_p95_ms':np.nan},np.full(len(ref),-1,dtype=int)
    c=processing.compare_annotations(ref,test,width)
    error=np.abs(c.matched_test_sample-c.matched_ref_sample)/FS*1000
    return dict(tp=c.tp,fp=c.fp,fn=c.fn,sensitivity=c.sensitivity,ppv=c.positive_predictivity,f1=2*c.tp/(2*c.tp+c.fp+c.fn),
                localization_median_ms=float(np.median(error)) if len(error) else np.nan,
                localization_p95_ms=float(np.percentile(error,95)) if len(error) else np.nan),c.matching_sample_nums.astype(int)

def interval_metrics(samples,normal=None):
    rr=np.diff(samples)/FS*1000
    eligible=np.ones(len(rr),bool) if normal is None else (normal[:-1]&normal[1:]&(rr>=300)&(rr<=2000))
    used=rr[eligible]; consecutive=eligible[:-1]&eligible[1:]
    return {'mean_nn_ms':float(np.mean(used)) if len(used) else np.nan,
            'sdnn_ms':float(np.std(used,ddof=1)) if len(used)>1 else np.nan,
            'rmssd_ms':float(np.sqrt(np.mean(np.diff(rr)[consecutive]**2))) if np.any(consecutive) else np.nan,
            'n_intervals':len(used)}

def qrs_record(name):
    x,ann,a,leads,rate=native('mitdb',name);x=x[:,0]
    ref=ann[np.array([s in BEATS for s in a.symbol])];start=60*FS;stop=len(x)-10*FS
    ref=ref[(ref>=start)&(ref<stop)];rows=[]
    for method,y in variants(x,'mitdb_'+name).items():
        pred=detect(y);pred=pred[(pred>=start)&(pred<stop)]
        for width in [38,13]:
            metrics,_=compare(ref,pred,width)
            rows.append(dict(record=name,subject=subject('mitdb',name),lead=leads[0],native_fs=rate,method=method,matching_width_samples=width,n_reference=len(ref),n_detected=len(pred),**metrics))
    return {'qrs':rows}

def hrv_record(name):
    x,ann,a,leads,rate=native('fantasia',name,1870);x=x[:,0]
    select=np.array([s in BEATS for s in a.symbol]);ref=ann[select];normal=np.array(a.symbol)[select]=='N';rows=[]
    for method,y in variants(x,'fantasia_'+name).items():
        pred=detect(y)
        for window in range(6):
            lo=(60+300*window)*FS;hi=lo+300*FS;mask=(ref>=lo)&(ref<hi);r=ref[mask];n=normal[mask];p=pred[(pred>=lo)&(pred<hi)]
            metrics,mapping=compare(r,p);truth=interval_metrics(r,n);estimated=interval_metrics(p)
            pair=(mapping[:-1]>=0)&(mapping[1:]==mapping[:-1]+1)&n[:-1]&n[1:]
            rr=np.diff(r)/FS*1000;pair&=(rr>=300)&(rr<=2000)
            indexes=np.flatnonzero(pair);e=[]
            for i in indexes:e.append(abs((p[mapping[i+1]]-p[mapping[i]])/FS*1000-rr[i]))
            row=dict(record=name,subject=subject('fantasia',name),lead=leads[0],method=method,window=window,start_s=lo/FS,n_reference=len(r),n_detected=len(p),matched_rr_pairs=len(e),eligible_reference_intervals=truth['n_intervals'],rr_mae_ms=float(np.mean(e)) if e else np.nan,**metrics)
            # Secondary annotation-assisted timing analysis on identical normal pairs.
            # This does not claim an autonomous ectopic-beat classifier or correct FN/FP.
            matched_reference=rr[indexes]
            matched_detected=np.array([(p[mapping[i+1]]-p[mapping[i]])/FS*1000 for i in indexes])
            row['matched_nn_coverage']=len(indexes)/truth['n_intervals'] if truth['n_intervals'] else np.nan
            row['matched_nn_sdnn_abs_error_ms']=abs(np.std(matched_detected,ddof=1)-np.std(matched_reference,ddof=1)) if len(indexes)>1 else np.nan
            consecutive=np.diff(indexes)==1
            row['matched_nn_rmssd_abs_error_ms']=abs(np.sqrt(np.mean(np.diff(matched_detected)[consecutive]**2))-np.sqrt(np.mean(np.diff(matched_reference)[consecutive]**2))) if np.any(consecutive) else np.nan
            for metric in ['mean_nn_ms','sdnn_ms','rmssd_ms']:
                row['reference_'+metric]=truth[metric];row['estimated_'+metric]=estimated[metric];row['abs_error_'+metric]=abs(estimated[metric]-truth[metric])
            rows.append(row)
    return {'hrv':rows}

def delineate_context(x,beats,lo,hi):
    import neurokit2 as nk
    lo=max(0,int(lo));hi=min(len(x),int(hi));r=beats[(beats>=lo+FS)&(beats<hi-FS)]
    if len(r)<5:return {},'fewer_than_5_context_beats'
    segment=x[lo:hi]/1000.;baseline=np.median(segment)
    orientation=1 if np.median(x[r]/1000.-baseline)>=0 else -1
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            _,waves=nk.ecg_delineate(orientation*segment,r-lo,sampling_rate=FS,method='dwt',check=False)
        on=np.asarray(waves['ECG_R_Onsets'],float);off=np.asarray(waves['ECG_R_Offsets'],float)
        points={}
        for peak,onset,offset in zip(r,on,off):
            if not np.isfinite(onset) or not np.isfinite(offset):continue
            onset=int(onset)+lo;offset=int(offset)+lo
            if not (0<=peak-onset<=round(.15*FS) and 0<=offset-peak<=round(.18*FS) and round(.04*FS)<=offset-onset<=round(.25*FS)):continue
            prior=np.searchsorted(beats,peak)-1
            if prior<0:continue
            st=offset+(15 if peak-beats[prior]<125 else 20)
            if onset-5<0 or st+3>=len(x):continue
            points[int(peak)]={'onset':onset,'j':offset,'st':st,'polarity':orientation}
        return points,''
    except Exception as error:return {},type(error).__name__+': '+str(error)

def st_level(x,point,jitter=0):
    st=point['st']+jitter;on=point['onset']
    return float(np.median(x[st-2:st+3])-np.median(x[on-5:on]))

def st_record(name):
    x,ann,a,leads,rate=native('edb',name,all_leads=True)
    beats=ann[np.array([s in BEATS for s in a.symbol])]
    events=[]
    for sample,note in zip(ann,a.aux_note):
        match=re.fullmatch(r'AST([01])([+-])(\d+)',note.strip('\x00 ').strip())
        if match:events.append({'peak_marker':int(sample),'channel':int(match[1]),'expert_deviation_uv':int(match[3])*(1 if match[2]=='+' else -1),'annotation':note})
    rows=[];failures=[];landmarks=[]
    for channel in [0,1]:
        signal=x[:,channel];chosen=[e for e in events if e['channel']==channel]
        if not chosen:continue
        base,base_error=delineate_context(signal,beats,0,40*FS)
        base={r:p for r,p in base.items() if FS<=r<30*FS}
        measured=variants(signal,'edb_'+name+'_lead'+str(channel))
        reference={}
        for jitter in [-2,0,2]:
            for method,y in measured.items():reference[(method,jitter)]=float(np.median([st_level(y,p,jitter) for p in base.values()])) if len(base)>=5 else np.nan
        for event in chosen:
            key=dict(record=name,subject=subject('edb',name),lead=leads[channel],**event,n_reference_beats=len(base))
            index=np.searchsorted(beats,event['peak_marker'],side='left')
            if index>=len(beats):failures.append(dict(**key,reason='no_following_beat'));continue
            target=int(beats[index]);points,error=delineate_context(signal,beats,target-12*FS,target+12*FS)
            if len(base)<5:failures.append(dict(**key,reason='reference_delineation: '+base_error));continue
            if target not in points:failures.append(dict(**key,reason='peak_delineation: '+error));continue
            point=points[target];landmarks.append(dict(**key,target_beat=target,**point))
            for jitter in [-2,0,2]:
                raw=st_level(signal,point,jitter)-reference[('unfiltered',jitter)]
                for method,y in measured.items():
                    estimate=st_level(y,point,jitter)-reference[(method,jitter)];err=estimate-event['expert_deviation_uv']
                    rows.append(dict(**key,target_beat=target,j_sample=point['j'],onset_sample=point['onset'],st_sample=point['st'],jitter_ms=jitter/FS*1000,method=method,reference_level_uv=reference[(method,jitter)],estimated_deviation_uv=estimate,error_uv=err,abs_error_uv=abs(err),change_from_unfiltered_uv=estimate-raw,sign_agreement=float(estimate*event['expert_deviation_uv']>0),threshold_retained=float(estimate*np.sign(event['expert_deviation_uv'])>=100)))
    return {'st':rows,'st_failures':failures,'st_landmarks':landmarks,'st_inventory':[dict(record=name,subject=subject('edb',name),expert_peaks=len(events),evaluated_peaks=len(landmarks),failed_peaks=len(failures),duration_s=len(x)/FS)]}

def execute(job):
    db,name=job
    try:result={'mitdb':qrs_record,'fantasia':hrv_record,'edb':st_record}[db](name)
    except MissingSignalError as error:result={'record_exclusions':[error.details]}
    (OUT/'record_cache'/f'{db}_{name}.json').write_text(json.dumps(result,allow_nan=True),encoding='utf-8')
    return db,name,{k:len(v) for k,v in result.items()}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--databases',nargs='+',default=['mitdb','fantasia','edb']);parser.add_argument('--workers',type=int,default=3);parser.add_argument('--limit',type=int);parser.add_argument('--force',action='store_true')
    args=parser.parse_args();OUT.mkdir(parents=True,exist_ok=True);(OUT/'record_cache').mkdir(exist_ok=True);compile_java();jobs=[]
    for db in args.databases:
        names=(DATA/'raw'/db/'RECORDS').read_text().split()
        if args.limit:names=names[:args.limit]
        for name in names:
            if args.force or not (OUT/'record_cache'/f'{db}_{name}.json').exists():jobs.append((db,name))
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        futures={pool.submit(execute,job):job for job in jobs}
        for future in as_completed(futures):print(future.result(),flush=True)
    tables={}
    for path in sorted((OUT/'record_cache').glob('*.json')):
        for key,rows in json.loads(path.read_text()).items():tables.setdefault(key,[]).extend(rows)
    for key,rows in tables.items():pd.DataFrame(rows).to_csv(OUT/(key+'.csv'),index=False)
    import neurokit2,scipy,platform
    meta={'python':platform.python_version(),'numpy':np.__version__,'scipy':scipy.__version__,'wfdb':wfdb.__version__,'neurokit2':neurokit2.__version__,'protocol_sha256':hashlib.sha256((ROOT/'docs/retrospective_protocol.json').read_bytes()).hexdigest(),'counts':{k:len(v) for k,v in tables.items()}}
    (OUT/'environment.json').write_text(json.dumps(meta,indent=2));print(meta,flush=True)

if __name__=='__main__':main()
