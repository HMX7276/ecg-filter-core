package com.ecg.filter.sqi;

import java.util.Arrays;
import com.ecg.filter.butterworth.SosZeroPhaseFilter;

/** Population SD of cascaded 200/600-ms median baseline, in input units.
 * At 250 Hz use odd widths 51 and 151, with nearest-endpoint extension.
 * Sliding sorted arrays use insertion/deletion, not sorting networks.
 */
public final class BaselineWanderAmplitude {
    public double compute(double[] signal, int samplingRate) {
        if (samplingRate != 250) throw new IllegalArgumentException("Only 250 Hz is supported");
        SosZeroPhaseFilter.validateSignal(signal, 1);
        double[] baseline = median(median(signal, 51), 151);
        double mean = 0, m2 = 0;
        for (int i=0; i<baseline.length; i++) {
            double d=baseline[i]-mean; mean += d/(i+1); m2 += d*(baseline[i]-mean);
        }
        return Math.sqrt(Math.max(0, m2 / baseline.length));
    }

    static double[] median(double[] x, int width) {
        int radius=width/2;
        double[] sorted=new double[width], out=new double[x.length];
        for(int j=0;j<width;j++) sorted[j]=x[Math.max(0,Math.min(x.length-1,j-radius))];
        Arrays.sort(sorted);
        for(int i=0;i<x.length;i++) {
            out[i]=sorted[radius];
            if(i+1==x.length) break;
            double old=x[Math.max(0,i-radius)];
            int pos=Arrays.binarySearch(sorted,old);
            System.arraycopy(sorted,pos+1,sorted,pos,width-pos-1);
            double next=x[Math.min(x.length-1,i+radius+1)];
            int at=Arrays.binarySearch(sorted,0,width-1,next);
            if(at<0) at=-at-1;
            System.arraycopy(sorted,at,sorted,at+1,width-1-at);
            sorted[at]=next;
        }
        return out;
    }
}
