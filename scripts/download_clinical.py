"""Cache public annotated ECG files; complete waveform records stay outside Git."""
import argparse, json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from download_data import DATA, fetch

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--databases',nargs='+',default=['mitdb','fantasia','edb'])
    args=parser.parse_args();selection={};jobs=[]
    for db in args.databases:
        meta=fetch(db,'RECORDS');names=(DATA/meta['path']).read_text().split()
        expected={'mitdb':48,'fantasia':40,'edb':90}[db]
        if len(names)!=expected:raise ValueError((db,len(names),expected))
        selection[db]=names
        for name in names:
            for ext in ['hea','dat', 'ecg' if db=='fantasia' else 'atr']:jobs.append((db,name+'.'+ext))
    files=[]
    with ThreadPoolExecutor(max_workers=4) as pool:
        for i,result in enumerate(pool.map(lambda pair:fetch(*pair),jobs)):
            files.append(result)
            if (i+1)%12==0: print(f'{i+1}/{len(jobs)} files cached',flush=True)
    out=DATA/'clinical_manifest.json'
    out.write_text(json.dumps({'selection':selection,'files':files},indent=2),encoding='utf-8')
    print(f'Complete: {len(files)} files. {out}',flush=True)

if __name__=='__main__':main()
