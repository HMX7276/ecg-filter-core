"""Verify real-device replay exports and summarize per-frame/session measurements."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from java_support import ROOT

OUT=ROOT/'results/android';BUILD=ROOT/'target/android-replay'
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--exports',type=Path);parser.add_argument('--imports',type=Path);args=parser.parse_args();OUT.mkdir(parents=True,exist_ok=True)
    if args.imports:
        for folder in sorted(args.imports.glob('session_*')):
            if folder.is_dir() and (folder/'results.json').is_file():shutil.copytree(folder,OUT/folder.name,dirs_exist_ok=True)
    if args.exports:
        for archive in sorted(args.exports.glob('session_*.zip')):
            with zipfile.ZipFile(archive) as z:
                if z.testzip() is not None:raise ValueError('Corrupt phone export '+str(archive))
                result=json.loads(z.read('results.json'));name=result['run']
                if not name.startswith('session_') or '/' in name or '\\' in name:raise ValueError('Invalid run name')
                dest=OUT/name;dest.mkdir(exist_ok=True)
                for entry in z.infolist():
                    if '/' in entry.filename or '\\' in entry.filename or entry.filename in {'.','..'}:raise ValueError('Unexpected nested archive path')
                    (dest/entry.filename).write_bytes(z.read(entry))
    runs=[];frames=[];replays=[];checks=[];conditions=[]
    manifest_path=BUILD/'build_manifest.json'
    if not manifest_path.exists():manifest_path=OUT/'build_manifest.json'
    expected=json.loads(manifest_path.read_text())
    apk=BUILD/'ecg-replay-research.apk'
    if not apk.exists():apk=ROOT/'android-replay/ecg-replay-research.apk'
    if not apk.exists():apk=ROOT.parent/'revision/revised_submission/ecg-replay-research.apk'
    if hashlib.sha256(apk.read_bytes()).hexdigest()!=expected['apk_sha256']:raise ValueError('Distributed APK hash mismatch')
    for filename,digest in expected['source_files'].items():
        if hashlib.sha256((ROOT/filename).read_bytes()).hexdigest()!=digest:raise ValueError('Source differs from measured APK: '+filename)
    references={}
    with zipfile.ZipFile(apk) as archive:
        for filename,digest in expected['assets'].items():
            data=archive.read('assets/'+filename)
            if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Embedded input/reference asset hash mismatch')
            if filename.endswith('_reference.f64'):references[filename]=np.frombuffer(data,dtype='<f8')
    candidates=sorted(OUT.glob('session_*/results.json'),key=lambda p:int(p.parent.name.rsplit('_',1)[1]))
    seen_pids=set();selection=[]
    for path in candidates:
        result=json.loads(path.read_text());
        if result.get('status')!='complete':continue
        if result['apk_sha256']!=expected['apk_sha256']:raise ValueError('APK hash does not match built source')
        if result['input_manifest']['source_files']!=expected['source_files']:raise ValueError('Embedded source manifest differs')
        pid=result.get('process_id')
        reason='selected' if len(runs)<3 and pid not in seen_pids else ('same_process' if pid in seen_pids else 'additional_run_after_first_three')
        selection.append(dict(run=result['run'],process_id=pid,status=reason))
        if reason!='selected':continue
        seen_pids.add(pid)
        runs.append(result)
        for item in result['inputs']:
            name=item['input'];session=result['session'];directory=path.parent
            if item['input_sha256']!=expected['assets'][name+'_input.f64']:raise ValueError('Input hash mismatch')
            actual=np.fromfile(directory/(name+'_output.f64'),dtype='<f8');reference=references[name+'_reference.f64']
            assert len(actual)==len(reference)==150000 and np.isfinite(actual).all()
            diff=actual-reference;checks.append(dict(session=session,run=result['run'],process_id=result.get('process_id'),input=name,samples=len(actual),rmse_uv=float(np.sqrt(np.mean(diff**2))),max_abs_uv=float(np.max(np.abs(diff)))))
            frame=pd.read_csv(directory/(name+'_frames.csv'))
            if len(frame)!=2400 or frame.duplicated(['replay','chunk']).any() or sorted(frame.replay.unique())!=list(range(20)):raise ValueError('Missing or duplicate measured calls')
            for _,calls in frame.groupby('replay'):
                if calls.chunk.tolist()!=list(range(120)) or calls.output_samples.tolist()!=[0]+[1250]*119 or not (calls.elapsed_ns>=0).all():raise ValueError('Invalid per-replay call sequence')
            frame['session']=session;frame['input']=name;frame['run']=result['run'];frames.append(frame)
            replay=pd.read_csv(directory/(name+'_replays.csv'))
            if replay.replay.tolist()!=list(range(20)) or not (replay.full_frames==119).all() or not (replay.finish_samples==1250).all():raise ValueError('Incomplete replay or terminal log')
            replay['session']=session;replay['input']=name;replays.append(replay)
            for stage,condition in [('before',item['before']),('after',item['after'])]+[(str(v['replay']),v) for v in item['environment_per_replay']]:
                conditions.append(dict(session=session,input=name,stage=stage,**{k:v for k,v in condition.items() if not isinstance(v,dict)}))
    if len(runs)!=3 or len({r['run'] for r in runs})!=3:raise ValueError(f'Expected three complete independent app runs, found {len(runs)}')
    (OUT/'session_selection.json').write_text(json.dumps({'rule':'First three chronologically complete runs with distinct process IDs; no selection by performance','runs':selection},indent=2))
    if len({r.get('process_id') for r in runs})!=3:raise ValueError('Three distinct process IDs were not recorded; review restart independence')
    device_keys=['manufacturer','model','device','fingerprint','android_release','api','abis','soc_model']
    if any(any(r['device'].get(k)!=runs[0]['device'].get(k) for k in device_keys) for r in runs[1:]):raise ValueError('Device/build metadata differs between analyzed sessions')
    frame=pd.concat(frames,ignore_index=True);replay=pd.concat(replays,ignore_index=True);check=pd.DataFrame(checks);condition=pd.DataFrame(conditions)
    if len(frame)!=21600 or len(replay)!=180 or len(check)!=9:raise ValueError('Incomplete per-call/replay/input logs')
    full=frame[frame.output_samples>0]
    if len(full)!=21420:raise ValueError('Expected 3 sessions x 3 inputs x 20 replays x 119 full frames')
    if check.max_abs_uv.max()>1e-4:raise ValueError('Device numerical discrepancy exceeds protocol bound')
    summary=[]
    for keys,g in full.groupby(['session','input']):
        t=g.elapsed_ns.to_numpy()/1e6;r=replay[(replay.session==keys[0])&(replay.input==keys[1])]
        summary.append(dict(session=keys[0],input=keys[1],frames=len(t),mean_ms=t.mean(),median_ms=np.median(t),p95_ms=np.quantile(t,.95,method='inverted_cdf'),max_ms=t.max(),mean_replay_compute_ms=r.compute_ns.mean()/1e6,observed_max_java_used_mib=r.java_used_observed_max.max()/2**20))
    pd.DataFrame(summary).to_csv(OUT/'session_summary.csv',index=False);frame.to_csv(OUT/'all_calls.csv',index=False);replay.to_csv(OUT/'all_replays.csv',index=False);check.to_csv(OUT/'host_output_checks.csv',index=False);condition.to_csv(OUT/'conditions.csv',index=False)
    pooled=[]
    for name,g in full.groupby('input'):
        t=g.elapsed_ns.to_numpy()/1e6;pooled.append(dict(input=name,frames=len(t),mean_ms=t.mean(),median_ms=np.median(t),p95_ms=np.quantile(t,.95,method='inverted_cdf'),max_ms=t.max()))
    pd.DataFrame(pooled).to_csv(OUT/'input_summary.csv',index=False)
    t=full.elapsed_ns.to_numpy()/1e6
    overall={'sessions':3,'inputs':3,'warmups_per_input_per_session':5,'measured_replays':180,'measured_full_frames':len(t),'mean_ms':float(t.mean()),'median_ms':float(np.median(t)),'p95_ms':float(np.quantile(t,.95,method='inverted_cdf')),'max_ms':float(t.max()),'max_output_rmse_uv':float(check.rmse_uv.max()),'max_output_abs_error_uv':float(check.max_abs_uv.max()),'observed_java_used_mib_max':float(replay.java_used_observed_max.max()/2**20),'sampled_pss_mib_max':float(condition.total_pss_kb.max()/1024),'battery_temperature_c_min':float(condition.battery_temperature_tenths_c.min()/10),'battery_temperature_c_max':float(condition.battery_temperature_tenths_c.max()/10),'thermal_statuses':sorted(map(int,condition.thermal_status.unique())),'power_save_states':list(map(bool,condition.power_save.unique())),'plugged_values':sorted(map(int,condition.plugged.unique())),'device':runs[0]['device'],'apk_sha256':expected['apk_sha256'],'scope':'Accelerated real-device replay, not sensor acquisition or alarm latency. Memory includes application replay/logging buffers; no minimum-heap claim.'}
    (OUT/'summary.json').write_text(json.dumps(overall,indent=2),encoding='utf-8')
    if manifest_path!=OUT/'build_manifest.json':shutil.copy2(manifest_path,OUT/'build_manifest.json')
    plt.rcParams.update({'font.size':10,'pdf.fonttype':42});fig,ax=plt.subplots(figsize=(6.5,3.4))
    labels=[];values=[]
    for (session,name),g in full.groupby(['session','input']):labels.append(f'S{session}\n{name.replace("fantasia_", "").replace("mitdb_", "")}');values.append(g.elapsed_ns/1e6)
    ax.boxplot(values,tick_labels=labels,showfliers=False);ax.set(ylabel='Full-frame computation (ms)',title='Physical Android replay; 2380 frames per box');ax.spines[['top','right']].set_visible(False)
    fig.tight_layout();fig.savefig(OUT/'fig9_android_replay.pdf',bbox_inches='tight');fig.savefig(OUT/'fig9_android_replay.png',dpi=300,bbox_inches='tight');plt.close(fig)
    print(json.dumps(overall,indent=2))

if __name__=='__main__':main()
