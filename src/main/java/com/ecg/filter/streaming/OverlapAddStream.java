package com.ecg.filter.streaming;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import com.ecg.filter.adaptive.FilterConfig;
import com.ecg.filter.adaptive.SessionCalibratedSelector;
import com.ecg.filter.butterworth.SosZeroPhaseFilter;
import com.ecg.filter.sqi.BaselineWanderAmplitude;
import com.ecg.filter.sqi.MuscleSQI;
import com.ecg.filter.sqi.CenteredMuscleSQI;

/** Bounded-buffer delayed stream: 250 Hz, 10-s frame, 5-s hop, normalized
 * raised-cosine overlap-add. No claim of equivalence to whole-record filtering.
 * First output needs 10 s of input; individual emitted samples are 5-10 s old.
 * State belongs to one recording. finish() explicitly marks end of recording.
 */
public final class OverlapAddStream {
    public static final int WINDOW=2500, HOP=1250;
    private final double[] input=new double[WINDOW], sum=new double[WINDOW], weight=new double[WINDOW];
    private final double[] taper=new double[WINDOW];
    private final SessionCalibratedSelector selector;
    private final FilterConfig fixed;
    private final SosZeroPhaseFilter filter=new SosZeroPhaseFilter();
    private final BaselineWanderAmplitude bwa=new BaselineWanderAmplitude();
    private final MuscleSQI muscle=new MuscleSQI();
    private final boolean centeredMuscle;
    private int count=0;
    private long received=0, emitted=0;
    private boolean closed=false, dirty=false;

    public OverlapAddStream() { this(new SessionCalibratedSelector(),null,true); }
    public OverlapAddStream(SessionCalibratedSelector selector) { this(selector,null,true); }
    /** For reproducing the DC-offset ablation; new sessions should use true. */
    public OverlapAddStream(SessionCalibratedSelector selector, boolean centerMuscle) { this(selector,null,centerMuscle); }
    public OverlapAddStream(FilterConfig fixed) { this(null,fixed,true); }
    private OverlapAddStream(SessionCalibratedSelector selector, FilterConfig fixed, boolean centerMuscle) {
        if(selector==null && fixed==null) throw new IllegalArgumentException("Selector or fixed config required");
        this.selector=selector; this.fixed=fixed;
        this.centeredMuscle=centerMuscle;
        for(int i=0;i<WINDOW;i++) taper[i]=0.5-0.5*Math.cos(2*Math.PI*(i+0.5)/WINDOW);
    }

    public double[] accept(double[] samples) {
        if(closed) throw new IllegalStateException("Stream is closed");
        SosZeroPhaseFilter.validateSignal(samples,0);
        List<double[]> blocks=new ArrayList<>(); int total=0;
        for(double sample:samples) {
            input[count++]=sample; received++; dirty=true;
            if(count==WINDOW) {
                accumulate(input); double[] block=emit(HOP); blocks.add(block); total+=block.length;
                System.arraycopy(input,HOP,input,0,WINDOW-HOP); count=WINDOW-HOP; dirty=false;
            }
        }
        double[] out=new double[total]; int offset=0;
        for(double[] b:blocks) { System.arraycopy(b,0,out,offset,b.length); offset+=b.length; }
        return out;
    }

    public double[] finish() {
        if(closed) throw new IllegalStateException("Stream is closed");
        closed=true;
        if(received==0) return new double[0];
        int remaining=(int)(received-emitted);
        if(dirty) {
            // End-only padding, not future observations. This terminal frame does
            // not update calibration or switching; retain last active config.
            double[] frame=Arrays.copyOf(input,WINDOW);
            for(int i=count;i<WINDOW;i++) frame[i]=frame[count-1];
            FilterConfig config=fixed!=null?fixed:selector.getCurrentConfig();
            add(filter.filter(frame,config));
        }
        return emit(remaining);
    }

    private void accumulate(double[] frame) {
        FilterConfig config=fixed!=null?fixed:selector.select(bwa.compute(frame,250),
                centeredMuscle?new CenteredMuscleSQI().compute(frame,250):muscle.compute(frame,250));
        add(filter.filter(frame,config));
    }
    private void add(double[] y) {
        for(int i=0;i<WINDOW;i++) { sum[i]+=taper[i]*y[i]; weight[i]+=taper[i]; }
    }
    private double[] emit(int n) {
        double[] out=new double[n];
        for(int i=0;i<n;i++) out[i]=sum[i]/weight[i];
        System.arraycopy(sum,n,sum,0,WINDOW-n); Arrays.fill(sum,WINDOW-n,WINDOW,0);
        System.arraycopy(weight,n,weight,0,WINDOW-n); Arrays.fill(weight,WINDOW-n,WINDOW,0);
        emitted+=n; return out;
    }
}
