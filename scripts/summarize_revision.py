"""Generate subject-clustered descriptive intervals, boundary figure, and benchmark metadata."""
import json
import os
import subprocess
import numpy as np
import pandas as pd
from revision_experiments import OUT,CACHE,bootstrap_mean,subject_id,savefig,plt
from java_support import compile_java,executable,BUILD

def main():
    s=pd.read_csv(OUT/'streaming_per_record.csv'); rows=[]
    for key,g in s.groupby(['database','initial_condition','policy']):
        for metric in ['bw_active_recall','powerline_active_recall','clean_false_alarm_rate','switches']:
            values=g.assign(subject=g.record.map(subject_id)).groupby('subject')[metric].mean()
            lo,hi=bootstrap_mean(values)
            rows.append(dict(database=key[0],initial_condition=key[1],policy=key[2],metric=metric,records=len(g),subjects=len(values),record_mean=g[metric].mean(),subject_mean=values.mean(),ci_low=lo,ci_high=hi))
    pd.DataFrame(rows).to_csv(OUT/'streaming_intervals.csv',index=False)
    b=pd.read_csv(OUT/'boundary_per_record.csv'); pivot=b.pivot(index='record',columns='method',values='seam_rmse_uv')
    fig,ax=plt.subplots(figsize=(6.7,3.8))
    for i,(_,r) in enumerate(pivot.iterrows()): ax.plot([0,1],r[['independent_10s','overlap_add']],color='#2c6285',alpha=.35,lw=.7)
    ax.set(xticks=[0,1],xticklabels=['Independent 10-s blocks','10-s frames / 5-s overlap'],ylabel='Seam-region RMSE vs whole record (uV)')
    fig.tight_layout(); savefig(fig,'fig2_boundary_error')
    columns=['rmse_uv','max_abs_uv','correlation']
    n=pd.read_csv(OUT/'numerical_per_record.csv'); n['database']=n.record.str.split('_').str[0]
    n.groupby(['database','realization','config'])[columns].agg(['mean','std','median','max','min']).to_csv(OUT/'numerical_summary.csv')
    st=pd.read_csv(OUT/'synthetic_st.csv'); summary=[]
    for method,g in st.groupby('method'):
        lo,hi=bootstrap_mean(g.st_mae_uv);summary.append(dict(method=method,n_templates=len(g),mean_mae_uv=g.st_mae_uv.mean(),sd=g.st_mae_uv.std(),median=g.st_mae_uv.median(),ci_low=lo,ci_high=hi,max_template_mean=g.st_mae_uv.max(),worst_beat_uv=g.st_max_abs_uv.max()))
    pd.DataFrame(summary).to_csv(OUT/'synthetic_st_summary.csv',index=False)
    compile_java()
    run=subprocess.run([executable('java'),'-cp',str(BUILD),'RevisionBenchmark',str(CACHE/'mitdb_100_raw_start_input.f64')],check=True,text=True,capture_output=True)
    bench=json.loads(run.stdout); bench['cpu_identifier']=os.environ.get('PROCESSOR_IDENTIFIER','unknown');bench['logical_processors']=os.cpu_count()
    (OUT/'desktop_benchmark.json').write_text(json.dumps(bench,indent=2));print(json.dumps(bench,indent=2))
    from render_figures import main as render_all_figures
    render_all_figures()

if __name__=='__main__':main()
