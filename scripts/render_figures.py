"""Regenerate all six manuscript figures directly from saved CSVs (no experiments rerun)."""
from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from java_support import ROOT
OUT=ROOT/'results/revision'
plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False,'pdf.fonttype':42})
def save(fig,name):
    fig.savefig(OUT/(name+'.pdf'),bbox_inches='tight')
    fig.savefig(OUT/(name+'.png'),dpi=300,bbox_inches='tight');plt.close(fig)

def main():
    n=pd.read_csv(OUT/'numerical_per_record.csv');fig,ax=plt.subplots(figsize=(6.3,3.7))
    for mode,label,marker in [('direct','Direct form / SciPy filtfilt','o'),('sos','SOS / SciPy sosfiltfilt','s')]:
        v=n[(n.config=='CONFIG_DEFAULT')&(n.realization==mode)]
        ax.plot(np.arange(len(v)),np.maximum(v.rmse_uv,1e-15),marker,ms=3,label=label)
    ax.axvline(47.5,color='.55',ls=':',lw=.8)
    ax.set(yscale='log',xlabel='Record index (48 MIT-BIH, then 8 Fantasia)',ylabel=r'RMSE ($\mu$V)');ax.legend(fontsize=8)
    fig.tight_layout();save(fig,'fig1_numerical_equivalence')
    b=pd.read_csv(OUT/'boundary_per_record.csv').pivot(index='record',columns='method',values='seam_rmse_uv')
    fig,ax=plt.subplots(figsize=(6.3,3.7))
    for _,row in b.iterrows():ax.plot([0,1],row[['independent_10s','overlap_add']],color='#286183',alpha=.4,lw=.8)
    ax.set(xticks=[0,1],xticklabels=['Independent 10 s blocks','10 s / 5 s overlap'],ylabel=r'Seam RMSE vs whole record ($\mu$V)')
    fig.tight_layout();save(fig,'fig2_boundary_error')
    stable=pd.read_csv(OUT/'stability.csv');fig,axes=plt.subplots(2,1,figsize=(6.3,5.3),layout='constrained')
    for ax,col,title in zip(axes,['direct_peak_ratio','sos_peak_ratio'],['Direct form (SciPy)','SOS (Java)']):
        v=stable.pivot(index='order',columns='cutoff_hz',values=col)
        im=ax.imshow(np.log10(v),aspect='auto',vmin=-1,vmax=np.log10(stable.direct_peak_ratio.max()))
        ax.set_xticks(range(5),['0.01','0.05','0.1','0.5','1']);ax.set_yticks(range(4),['2','4','6','8'])
        ax.set(title=title,xlabel='High-pass cutoff (Hz)',ylabel='Order')
        for i in range(4):
            for j in range(5):
                value=v.iloc[i,j];label=f'{value:.2f}' if value<10 else f'{value:.1e}'
                ax.text(j,i,label,ha='center',va='center',fontsize=9,color='black' if np.log10(value)>20 else 'white')
    fig.colorbar(im,ax=axes,label='log10 output/input peak ratio',shrink=.8);save(fig,'fig3_stability')
    s=pd.read_csv(OUT/'streaming_per_record.csv');s=s[s.policy=='session']
    variants=['raw_start','bw_start_6dB','bw_start_0dB','ma_start_0dB']
    fig,axes=plt.subplots(1,2,figsize=(6.3,3.5),layout='constrained')
    for ax,col,title in zip(axes,['bw_active_recall','clean_false_alarm_rate'],['Baseline episode recall','No-injection selection rate']):
        ax.boxplot([s[s.initial_condition==v][col] for v in variants],tick_labels=['Raw','BW +6','BW 0','MA 0'],flierprops={'markersize':3})
        ax.set(title=title,xlabel='Initial contamination (dB)',ylim=(-.05,1.05));ax.tick_params(axis='x',labelsize=8)
    save(fig,'fig4_calibration_sensitivity')
    windows=pd.read_csv(OUT/'streaming_per_window.csv');fig,axes=plt.subplots(3,1,figsize=(6.3,6.5),sharex=True,layout='constrained')
    for ax,key in zip(axes,['mitdb_100','mitdb_207','fantasia_f1o01']):
        w=windows[windows.record==key];candidate=w.candidate.astype(float).copy();candidate[w.start_s<55]=np.nan
        ax.axvspan(0,60,color='.7',alpha=.2)
        for a,b in [(120,180),(300,360),(420,480)]:ax.axvspan(a,b,color='#d98f45',alpha=.18)
        ax.step(w.start_s+10,w.active,where='post',label='Active',color='#1b627f',lw=1.4)
        ax.step(w.start_s+10,candidate,where='post',ls='--',label='Candidate',color='#bc5e21',lw=1)
        ax.set(title=key,yticks=[0,1,2,3],ylabel='Configuration')
    axes[0].legend(fontsize=8,loc='upper right');axes[-1].set_xlabel('Decision availability time (s)')
    save(fig,'fig5_streaming')
    st=pd.read_csv(OUT/'synthetic_st.csv');methods=['zero_phase_0.05','zero_phase_0.10','causal_0.05','causal_twice_0.05','overlap_0.05']
    fig,ax=plt.subplots(figsize=(6.3,3.6));ax.boxplot([st[st.method==m].st_mae_uv for m in methods],tick_labels=['Zero .05','Zero .10','Causal .05','Causal x2','Overlap .05'],flierprops={'markersize':3})
    ax.set(ylabel=r'ST mean absolute error ($\mu$V)',xlabel='Filtering method');ax.tick_params(axis='x',labelsize=9)
    fig.tight_layout();save(fig,'fig6_synthetic_st');print('Regenerated six figures from CSV.')

if __name__=='__main__':main()
