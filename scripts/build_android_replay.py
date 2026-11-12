"""Build a permission-free, non-debuggable Android replay APK using installed SDK tools."""
import hashlib,json,os,shutil,subprocess,zipfile
from pathlib import Path
from java_support import ROOT,executable,compile_java,run_manifest

def run(args):subprocess.run([str(v) for v in args],check=True)
def main():
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'AppData/Local/Android/Sdk')))
    build_tools=sdk/'build-tools/36.1.0'
    out=ROOT/'target/android-replay';out.mkdir(parents=True,exist_ok=True)
    platform=out/'android-35.jar'
    if not platform.exists():shutil.copy2(sdk/'platforms/android-35/android.jar',platform)
    assets=out/'assets';assets.mkdir(exist_ok=True);classes=out/'classes';classes.mkdir(exist_ok=True)
    ids=['mitdb_100','mitdb_207','fantasia_f1o01'];jobs=[]
    compile_java()
    for name in ids:
        source=ROOT/'results/revision/work'/f'{name}_raw_start_input.f64'
        if not source.exists():raise FileNotFoundError(f'Run revision_experiments.py to regenerate {source}')
        target=assets/f'{name}_input.f64';shutil.copy2(source,target)
        jobs.append(['adaptive',target,assets/f'{name}_reference.f64','CONFIG_DEFAULT',1250])
    run_manifest(jobs,out)
    sources=sorted((ROOT/'src/main/java').rglob('*.java'))+sorted((ROOT/'android-replay/src').rglob('*.java'))
    manifest={'protocol':'docs/retrospective_protocol.json','inputs':ids,'format':'little-endian float64 microvolts, 250 Hz','source_files':{},'assets':{}}
    for p in sources:manifest['source_files'][str(p.relative_to(ROOT)).replace('\\','/')]=hashlib.sha256(p.read_bytes()).hexdigest()
    for p in sorted(assets.glob('*.f64')):manifest['assets'][p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
    (assets/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    run([executable('javac'),'--release','8','-encoding','UTF-8','-classpath',platform,'-d',classes]+sources)
    java=executable('java')
    run([java,'-cp',build_tools/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',platform,'--output',out]+sorted(classes.rglob('*.class')))
    resource_apk=out/'resources.apk'
    run([build_tools/'aapt2.exe','link','-o',resource_apk,'--manifest',ROOT/'android-replay/AndroidManifest.xml','-I',platform,'-A',assets,'--min-sdk-version','26','--target-sdk-version','35'])
    unsigned=out/'unsigned.apk';shutil.copy2(resource_apk,unsigned)
    with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:z.write(out/'classes.dex','classes.dex')
    aligned=out/'aligned.apk';run([build_tools/'zipalign.exe','-f','4',unsigned,aligned])
    key=out/'research-test-only.jks'
    if not key.exists():run([executable('keytool'),'-genkeypair','-keystore',key,'-storepass','research-only','-keypass','research-only','-alias','ecg-research','-dname','CN=ECG Replay Research Test','-keyalg','RSA','-keysize','2048','-validity','3650'])
    apk=out/'ecg-replay-research.apk'
    run([java,'-jar',build_tools/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','ecg-research','--ks-pass','pass:research-only','--key-pass','pass:research-only','--out',apk,aligned])
    run([java,'-jar',build_tools/'lib/apksigner.jar','verify','--verbose',apk])
    manifest['apk_sha256']=hashlib.sha256(apk.read_bytes()).hexdigest();manifest['build_tools']='36.1.0';manifest['compile_sdk']=35;manifest['min_sdk']=26;manifest['target_sdk']=35;manifest['debuggable']=False
    (out/'build_manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8');print(apk,flush=True)

if __name__=='__main__':main()
