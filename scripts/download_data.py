"""Fetch public PhysioNet files; cache originals and SHA256; never substitute synthetic records.
MIT-BIH: all 48 records. Fantasia: f1o01-04 and f1y01-04 (prespecified balanced pilot).
First 600 s; MLII if present, otherwise first ECG channel; resample to 250 Hz.
"""
import hashlib
import json
import math
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import numpy as np
import wfdb
from scipy.signal import resample_poly
from java_support import ROOT

DATA=ROOT/'data'

def fetch(db, name):
    path=DATA/'raw'/db/name
    path.parent.mkdir(parents=True,exist_ok=True)
    url=f'https://physionet.org/files/{db}/1.0.0/{name}'
    if not path.exists():
        session=requests.Session(); session.trust_env=False
        session.mount('https://',HTTPAdapter(max_retries=Retry(total=3,backoff_factor=1,status_forcelist=[429,500,502,503,504])))
        response=session.get(url,timeout=120); response.raise_for_status()
        path.write_bytes(response.content)
    return {'url':url,'path':str(path.relative_to(DATA)), 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}

def prepare(item):
    db,name=item; files=[fetch(db,name+'.hea'),fetch(db,name+'.dat')]
    record_path=str(DATA/'raw'/db/name)
    header=wfdb.rdheader(record_path)
    if db=='nstdb': channel=0
    else:
        channel=next((i for i,s in enumerate(header.sig_name) if s.upper()=='MLII'),
                     next((i for i,s in enumerate(header.sig_name) if 'ECG' in s.upper()),0))
    record=wfdb.rdrecord(record_path,sampto=min(header.sig_len,round(header.fs*600)),channels=[channel])
    unit=record.units[0]
    factor={'mV':1000.,'uV':1.,'µV':1.}.get(unit)
    if factor is None: raise ValueError(f'Unknown units {unit}: {db}/{name}')
    rate=round(header.fs)
    if rate!=header.fs: raise ValueError('Noninteger rate needs explicit resampling policy')
    gcd=math.gcd(250,rate)
    x=resample_poly(record.p_signal[:,0]*factor,250//gcd,rate//gcd)
    if not np.isfinite(x).all() or len(x)!=150000: raise ValueError(f'Invalid length/values: {db}/{name}')
    out=DATA/'processed'/f'{db}_{name}.npz'; out.parent.mkdir(parents=True,exist_ok=True)
    np.savez_compressed(out,x=x,fs=250)
    return {'database':db,'record':name,'lead':record.sig_name[0],'original_fs':header.fs,
            'original_units':unit,'output_units':'uV','samples':len(x),'duration_s':600,'files':files}

def main():
    record_file=fetch('mitdb','RECORDS')
    names=(DATA/record_file['path']).read_text().split()
    if len(names)!=48: raise ValueError('Unexpected MIT-BIH record count')
    jobs=[('mitdb',n) for n in names]
    jobs += [('fantasia',f'f1{age}{i:02d}') for age in ['o','y'] for i in range(1,5)]
    jobs += [('nstdb',n) for n in ['bw','em','ma']]
    records=[]
    with ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(prepare,jobs):
            records.append(result); print(result['database'],result['record'],flush=True)
    (DATA/'manifest.json').write_text(json.dumps({'selection':'all 48 MIT-BIH; first four f1 old and young Fantasia; NSTDB bw/em/ma; first 600 s', 'records':records},indent=2),encoding='utf-8')

if __name__=='__main__': main()
