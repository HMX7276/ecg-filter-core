"""Install this research APK, collect three real-device sessions and verify outputs.
Never fabricates device data; exits with a clear reason when no authorized phone exists.
"""
import argparse,hashlib,json,os,subprocess,time
from pathlib import Path
import numpy as np
from java_support import ROOT

PACKAGE='org.ecgrevision.replay'
REMOTE='/sdcard/Android/data/'+PACKAGE+'/files'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial');parser.add_argument('--wait-device',type=int,default=0);args=parser.parse_args()
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'AppData/Local/Android/Sdk')));adb=sdk/'platform-tools/adb.exe'
    def call(parts,serial=None,check=True):
        cmd=[str(adb)]+(['-s',serial] if serial else [])+parts
        return subprocess.run(cmd,check=check,text=True,capture_output=True,encoding='utf-8',errors='replace')
    deadline=time.monotonic()+args.wait_device;serial=None
    while True:
        rows=[line.split() for line in call(['devices']).stdout.splitlines()[1:] if line.strip()]
        devices=[row[0] for row in rows if len(row)>1 and row[1]=='device' and not row[0].startswith('emulator-')]
        if args.serial:devices=[s for s in devices if s==args.serial]
        if len(devices)>1:raise RuntimeError('More than one phone: select --serial explicitly')
        if devices:serial=devices[0];break
        if time.monotonic()>=deadline:raise RuntimeError('No authorized physical Android device found; connect the phone and allow USB debugging')
        print('Waiting for an authorized physical phone...',flush=True);time.sleep(10)
    def shell(*parts,check=True):return call(['shell']+list(parts),serial,check)
    props={key:shell('getprop',key).stdout.strip() for key in ['ro.product.manufacturer','ro.product.model','ro.product.marketname','ro.build.fingerprint','ro.kernel.qemu']}
    if props['ro.kernel.qemu']=='1':raise RuntimeError('Emulator is not eligible for this physical-device experiment')
    if 'xiaomi' not in props['ro.product.manufacturer'].lower():raise RuntimeError('Expected user-specified Xiaomi phone; found '+str(props))
    out=ROOT/'results/android';out.mkdir(parents=True,exist_ok=True)
    build=ROOT/'target/android-replay';apk=build/'ecg-replay-research.apk'
    # Keep previous installed-package state visible to the user, without clearing unrelated apps or device settings.
    install=call(['install','-r',str(apk)],serial);print(install.stdout,flush=True)
    (out/'host_install.txt').write_text(install.stdout+install.stderr,encoding='utf-8')
    (out/'host_build_manifest.json').write_text((build/'build_manifest.json').read_text(),encoding='utf-8')
    outputs=[]
    for session in range(1,4):
        shell('am','force-stop',PACKAGE)
        start=time.time();launch=shell('am','start','-W','-n',PACKAGE+'/.ReplayActivity','--ez','autorun','true','--ei','session',str(session))
        (out/f'launch_{session}.txt').write_text(launch.stdout+launch.stderr,encoding='utf-8')
        print('Started physical-device session',session,flush=True)
        run=None;deadline=time.monotonic()+600
        while time.monotonic()<deadline:
            latest=shell('cat',REMOTE+'/latest_run.txt',check=False).stdout.strip()
            if latest.startswith('session_'+str(session)+'_'):
                stamp=int(latest.rsplit('_',1)[1])/1000
                if stamp>=start-2 and shell('cat',REMOTE+'/'+latest+'/DONE',check=False).stdout.strip()=='complete':run=latest;break
            time.sleep(3)
        if run is None:
            log=call(['logcat','-d','-s','ECGReplay:I','AndroidRuntime:E','*:S'],serial,False)
            (out/f'failure_log_{session}.txt').write_text(log.stdout,encoding='utf-8')
            raise RuntimeError('Device session did not finish; partial logs saved')
        destination=out/run;call(['pull',REMOTE+'/'+run,str(destination)],serial)
        result=json.loads((destination/'results.json').read_text());assert result['status']=='complete'
        for item in result['inputs']:
            name=item['input'];actual=np.fromfile(destination/(name+'_output.f64'),dtype='<f8');reference=np.fromfile(build/'assets'/(name+'_reference.f64'),dtype='<f8')
            if len(actual)!=len(reference) or not np.isfinite(actual).all():raise RuntimeError('Invalid device output')
            delta=actual-reference;outputs.append(dict(session=session,input=name,samples=len(actual),rmse_uv=float(np.sqrt(np.mean(delta*delta))),max_abs_uv=float(np.max(np.abs(delta))),output_sha256=hashlib.sha256((destination/(name+'_output.f64')).read_bytes()).hexdigest()))
        print('Collected',run,flush=True)
    meta={'device':props,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'outputs':outputs,'serial_recorded':False,'independent_process_sessions':3}
    (out/'host_verification.json').write_text(json.dumps(meta,indent=2),encoding='utf-8');print('Three real-device sessions complete.',flush=True)

if __name__=='__main__':main()
