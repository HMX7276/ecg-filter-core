"""Participant-level summaries and scientific plots from frozen endpoint tables."""
import json,shutil,hashlib
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.stats import wilcoxon,shapiro,rankdata
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from clinical_experiments import OUT,ROOT,METHODS,subject

LABELS=['Unfiltered','Whole SOS','Fixed overlap','Adaptive overlap']
def interval(values):
    x=np.asarray(values,float);x=x[np.isfinite(x)]
    if not len(x):return np.nan,np.nan
    rng=np.random.default_rng(20261112)
    means=x[rng.integers(0,len(x),size=(5000,len(x)))].mean(axis=1)
    return tuple(np.percentile(means,[2.5,97.5]))

def subjects(table,metric):return table.groupby(['subject','method'])[metric].mean().unstack('method').reindex(columns=METHODS)

def signed_rank_test(differences):
    d=np.asarray(differences,float);d=d[np.isfinite(d)&(d!=0)]
    if not len(d):return 1.,'all zero',0
    if len(d)<=20:
        ranks=rankdata(np.abs(d));sums=np.array([0.])
        for rank in ranks:sums=np.concatenate([sums,sums+rank])
        total=ranks.sum();observed=min(ranks[d>0].sum(),ranks[d<0].sum())
        p=float(np.mean(np.minimum(sums,total-sums)<=observed+1e-12))
        return p,'exact sign enumeration',len(d)
    return float(wilcoxon(d,alternative='two-sided',zero_method='wilcox',method='approx').pvalue),'normal approximation',len(d)
def descriptives(table,metrics,domain):
    rows=[]
    for metric in metrics:
        pivot=subjects(table,metric)
        for method in METHODS:
            v=pivot[method].dropna();lo,hi=interval(v)
            rows.append(dict(domain=domain,metric=metric,method=method,participants=len(v),mean=v.mean(),sd=v.std(),median=v.median(),ci_low=lo,ci_high=hi))
    return rows

def paired(table,metric,domain):
    pivot=subjects(table,metric);p=pivot[['fixed_overlap_default','adaptive_overlap']].dropna();d=p.adaptive_overlap-p.fixed_overlap_default
    lo,hi=interval(d)
    test,calculation,n_nonzero=signed_rank_test(d)
    normality=float(shapiro(d).pvalue) if len(d)>=3 and np.std(d)>0 else None
    return dict(domain=domain,metric=metric,n_participants=len(d),n_nonzero_pairs=n_nonzero,p_calculation=calculation,difference='adaptive minus fixed overlap',mean_difference=d.mean(),median_difference=d.median(),ci_low=lo,ci_high=hi,wilcoxon_p=test,shapiro_p=normality)

def save(fig,name):
    fig.savefig(OUT/(name+'.pdf'),bbox_inches='tight');fig.savefig(OUT/(name+'.png'),dpi=300,bbox_inches='tight');plt.close(fig)

def plot_boxes(ax,values,labels,ylabel,scale=1.):
    arrays=[np.asarray(v.dropna())*scale for v in values]
    ax.boxplot(arrays,tick_labels=labels,showfliers=True,patch_artist=True,boxprops={'facecolor':'#e4edf3'},medianprops={'color':'#d26722','linewidth':1.3},flierprops={'markersize':3,'markeredgecolor':'#53636f'})
    ax.set_ylabel(ylabel);ax.tick_params(axis='x',labelrotation=18);ax.spines[['top','right']].set_visible(False)

def main():
    q=pd.read_csv(OUT/'qrs.csv');q=q[q.matching_width_samples==38]
    h=pd.read_csv(OUT/'hrv.csv');st=pd.read_csv(OUT/'st.csv');s=st[st.jitter_ms==0];inventory=pd.read_csv(OUT/'st_inventory.csv')
    excluded=pd.read_csv(OUT/'record_exclusions.csv') if (OUT/'record_exclusions.csv').exists() else pd.DataFrame(columns=['database','record'])
    expected={'qrs_records':48,'hrv_records':40,'st_records':90}
    actual={'qrs_records':q.record.nunique(),'hrv_records':h.record.nunique()+len(excluded[excluded.database=='fantasia']),'st_records':len(inventory)}
    if actual!=expected:raise ValueError(('Incomplete cohort',actual))
    rows=descriptives(q,['sensitivity','ppv','f1','localization_median_ms','localization_p95_ms'],'mitdb')
    rows+=descriptives(h,['abs_error_mean_nn_ms','abs_error_sdnn_ms','abs_error_rmssd_ms','rr_mae_ms','matched_nn_coverage','matched_nn_sdnn_abs_error_ms','matched_nn_rmssd_abs_error_ms','f1'],'fantasia')
    rows+=descriptives(s,['abs_error_uv','error_uv','sign_agreement','threshold_retained'],'edb')
    pd.DataFrame(rows).to_csv(OUT/'participant_summary.csv',index=False)
    comparisons=[paired(q,'f1','mitdb'),paired(h,'abs_error_sdnn_ms','fantasia'),paired(h,'abs_error_rmssd_ms','fantasia'),paired(s,'abs_error_uv','edb')]
    tests=pd.DataFrame(comparisons);order=np.argsort(tests.wilcoxon_p.to_numpy());adjusted=np.empty(len(tests));running=0
    for rank,index in enumerate(order):running=max(running,min(1,tests.loc[index,'wilcoxon_p']*(len(tests)-rank)));adjusted[index]=running
    tests['holm_p']=adjusted;tests.to_csv(OUT/'paired_statistics.csv',index=False)
    (OUT/'subject_map.csv').write_text('database,record,subject\n'+''.join(f'{db},{name},{subject(db,name)}\n' for db in ['mitdb','fantasia','edb'] for name in ((ROOT/'data/raw'/db/'RECORDS') if (ROOT/'data/raw'/db/'RECORDS').exists() else (ROOT/'docs/record_lists'/(db+'.txt'))).read_text().split()),encoding='utf-8')
    q.groupby('method')[['tp','fp','fn']].sum().to_csv(OUT/'qrs_pooled_counts.csv')
    sensitivity=[]
    for jitter,g in st.groupby('jitter_ms'):
        for method,v in subjects(g,'abs_error_uv').items():
            lo,hi=interval(v);sensitivity.append(dict(jitter_ms=jitter,method=method,participants=v.notna().sum(),mean_absolute_error_uv=v.mean(),ci_low=lo,ci_high=hi))
    pd.DataFrame(sensitivity).to_csv(OUT/'st_landmark_sensitivity.csv',index=False)
    failures=pd.read_csv(OUT/'st_failures.csv')
    cov={'selected_mitdb_records':48,'mitdb_participants':q.subject.nunique(),'selected_fantasia_subjects':40,'evaluated_fantasia_subjects':h.subject.nunique(),'excluded_fantasia_subjects':len(excluded[excluded.database=='fantasia']),'hrv_windows_per_method':int(len(h)/4),'selected_edb_records':90,'edb_participants_in_inventory':inventory.subject.nunique(),'expert_st_peaks':int(inventory.expert_peaks.sum()),'evaluated_st_peaks':int(inventory.evaluated_peaks.sum()),'failed_st_peaks':int(inventory.failed_peaks.sum()),'edb_records_with_evaluable_peaks':s.record.nunique(),'edb_participants_with_evaluable_peaks':s.subject.nunique(),'failure_reasons':{str(k):int(v) for k,v in failures.reason.value_counts().items()},'scope':'Retrospective clinical signal/annotation endpoints; no prospective deployment, autonomous ST episode detection or clinical safety claim'}
    (OUT/'coverage.json').write_text(json.dumps(cov,indent=2),encoding='utf-8')
    shutil.copy2(ROOT/'data/clinical_manifest.json',OUT/'source_data_manifest.json');shutil.copy2(ROOT/'docs/retrospective_protocol.json',OUT/'protocol.json')
    environment=json.loads((OUT/'environment.json').read_text())
    environment.setdefault('cohort_execution_protocol_sha256',environment['protocol_sha256'])
    environment['protocol_sha256']=hashlib.sha256((OUT/'protocol.json').read_bytes()).hexdigest()
    (OUT/'environment.json').write_text(json.dumps(environment,indent=2))
    plt.rcParams.update({'font.size':11,'font.family':'DejaVu Sans','pdf.fonttype':42,'axes.labelsize':11})
    fig,axes=plt.subplots(1,3,figsize=(9.5,3.9))
    short_labels=['Raw','Whole','Fixed','Adaptive']
    pivot=subjects(q,'f1');plot_boxes(axes[0],[pivot[m] for m in METHODS],short_labels,'Participant mean F1 (%)',100);axes[0].set_title('MIT-BIH: fixed XQRS')
    for ax,metric,title in [(axes[1],'abs_error_sdnn_ms','Fantasia: SDNN'),(axes[2],'abs_error_rmssd_ms','Fantasia: RMSSD')]:
        p=subjects(h,metric);plot_boxes(ax,[p[m] for m in METHODS],short_labels,'Mean absolute error (ms)');ax.set_title(title)
    fig.tight_layout();save(fig,'fig7_clinical_qrs_hrv')
    fig,axes=plt.subplots(1,2,figsize=(8.3,4.0));p=subjects(s,'abs_error_uv')
    plot_boxes(axes[0],[p[m] for m in METHODS],short_labels,'Mean absolute ST error (uV)');axes[0].set_title('Expert peak-deviation reference')
    for method,color,marker in [('unfiltered','#647989','o'),('adaptive_overlap','#d26722','x')]:
        g=s[s.method==method];axes[1].scatter(g.expert_deviation_uv,g.estimated_deviation_uv,s=11,alpha=.5,label=LABELS[METHODS.index(method)],color=color,marker=marker)
    bounds=[min(s.expert_deviation_uv.min(),s.estimated_deviation_uv.min()),max(s.expert_deviation_uv.max(),s.estimated_deviation_uv.max())];axes[1].plot(bounds,bounds,'k--',lw=.8);axes[1].set(xlabel='Expert ST deviation (uV)',ylabel='Estimated ST deviation (uV)',title='Evaluable annotated peaks');axes[1].legend(fontsize=10);axes[1].spines[['top','right']].set_visible(False)
    fig.tight_layout();save(fig,'fig8_clinical_st')
    print(json.dumps(cov,indent=2));print(tests.to_string(index=False))

if __name__=='__main__':main()
