package com.ecg.filter.sqi;

import com.ecg.filter.butterworth.SosZeroPhaseFilter;

/** High-frequency energy ratio after removal of the per-window mean.
 * Both numerator and denominator use centered samples. This avoids arbitrary
 * acquisition DC offsets suppressing the ratio. It does not remove baseline drift.
 */
public final class CenteredMuscleSQI {
    public double compute(double[] x, int samplingRate) {
        SosZeroPhaseFilter.validateSignal(x,1);
        double mean=0;
        for(int i=0;i<x.length;i++) mean+=(x[i]-mean)/(i+1);
        double[] centered=new double[x.length];
        for(int i=0;i<x.length;i++) centered[i]=x[i]-mean;
        return new MuscleSQI().compute(centered,samplingRate);
    }
}
