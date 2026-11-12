package com.ecg.filter.adaptive;

import java.util.Arrays;

/** BWA/mSQI routing with frozen session thresholds and explicit calibration status.
 * Calibration is assumed usable; this class cannot establish that it is clean.
 * For 10-s windows / 5-s hop, 11 frames cover the first 60 s. Units must be uV.
 */
public final class SessionCalibratedSelector {
    private final int calibrationFrames, hysteresisFrames;
    private final double[] baseline, muscle;
    private int count=0, pendingCount=0;
    private double baselineThreshold=Double.NaN, muscleThreshold=Double.NaN;
    private FilterConfig current=FilterConfig.CONFIG_DEFAULT, pending=FilterConfig.CONFIG_DEFAULT;

    public SessionCalibratedSelector() { this(11,3); }
    public SessionCalibratedSelector(int calibrationFrames, int hysteresisFrames) {
        if(calibrationFrames<3 || hysteresisFrames<1) throw new IllegalArgumentException("Invalid frame counts");
        this.calibrationFrames=calibrationFrames; this.hysteresisFrames=hysteresisFrames;
        baseline=new double[calibrationFrames]; muscle=new double[calibrationFrames];
    }
    /** Explicit frozen thresholds for batch and transfer experiments. */
    public SessionCalibratedSelector(double baselineThreshold, double muscleThreshold, int hysteresisFrames) {
        this(3,hysteresisFrames);
        if(!Double.isFinite(baselineThreshold)||baselineThreshold<0||!Double.isFinite(muscleThreshold)||muscleThreshold<0)
            throw new IllegalArgumentException("Invalid thresholds");
        this.baselineThreshold=baselineThreshold; this.muscleThreshold=muscleThreshold;
        count=calibrationFrames;
    }

    public FilterConfig select(double bwa, double msqi) {
        if(!Double.isFinite(bwa)||bwa<0||!Double.isFinite(msqi)||msqi<0||msqi>1)
            throw new IllegalArgumentException("Invalid SQI");
        if(!isCalibrated()) {
            baseline[count]=bwa; muscle[count]=msqi; count++;
            if(isCalibrated()) {
                baselineThreshold=threshold(baseline);
                muscleThreshold=Math.max(0.05,threshold(muscle));
            }
            return current;
        }
        FilterConfig target=decide(bwa,msqi);
        if(target==current) { pending=current; pendingCount=0; }
        else {
            pendingCount=target==pending ? pendingCount+1 : 1; pending=target;
            if(pendingCount>=hysteresisFrames) { current=pending; pendingCount=0; }
        }
        return current;
    }

    public FilterConfig decide(double bwa, double msqi) {
        if(!isCalibrated()) return FilterConfig.CONFIG_DEFAULT;
        boolean b=bwa>baselineThreshold, m=msqi>muscleThreshold;
        return b ? (m?FilterConfig.CONFIG_CONSERVATIVE:FilterConfig.CONFIG_BASELINE_HEAVY)
                 : (m?FilterConfig.CONFIG_MUSCLE_HEAVY:FilterConfig.CONFIG_DEFAULT);
    }
    public boolean isCalibrated() { return count>=calibrationFrames; }
    public double getBaselineThreshold() { return baselineThreshold; }
    public double getMuscleThreshold() { return muscleThreshold; }
    public FilterConfig getCurrentConfig() { return current; }
    public static double threshold(double[] values) {
        double median=median(values), dev[]=new double[values.length];
        for(int i=0;i<values.length;i++) dev[i]=Math.abs(values[i]-median);
        return median+3*1.4826*median(dev);
    }
    private static double median(double[] values) {
        double[] a=values.clone(); Arrays.sort(a); int n=a.length;
        return n%2==1?a[n/2]:0.5*(a[n/2-1]+a[n/2]);
    }
}
