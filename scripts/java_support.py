"""Compile dependency-free Java and use a binary bridge, on Windows or POSIX."""
import os
import shutil
import subprocess
from pathlib import Path
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
BUILD=ROOT/'target/revision-classes'

def executable(name):
    home=os.environ.get('JAVA_HOME')
    candidates=[str(Path(home)/'bin'/name)] if home else []
    candidates += [shutil.which(name), str(Path('C:/Program Files/Android/Android Studio/jbr/bin')/(name+'.exe'))]
    for value in candidates:
        if value and (Path(value).exists() or Path(value+'.exe').exists()): return value
    raise RuntimeError('Install JDK 17+ and set JAVA_HOME')

def compile_java():
    BUILD.mkdir(parents=True, exist_ok=True)
    sources=sorted((ROOT/'src/main/java').rglob('*.java'))+list((ROOT/'scripts/java').glob('*.java'))
    subprocess.run([executable('javac'),'--release','8','-encoding','UTF-8','-d',str(BUILD)]+[str(p) for p in sources],check=True)

def run_manifest(jobs, directory):
    path=Path(directory)/'java_jobs.tsv'
    path.write_text('\n'.join('\t'.join(map(str,job)) for job in jobs),encoding='utf-8')
    subprocess.run([executable('java'),'-cp',str(BUILD),'RevisionRunner','manifest',str(path)],check=True)

def java_filter(x, directory, mode='sos', config='CONFIG_DEFAULT', extra=None):
    directory=Path(directory); directory.mkdir(parents=True,exist_ok=True)
    source=directory/'input.f64'; target=directory/'output.f64'
    np.asarray(x,dtype='<f8').tofile(source)
    args=[mode,source,target,config]+([] if extra is None else [extra])
    run_manifest([args],directory)
    return np.fromfile(target,dtype='<f8')
