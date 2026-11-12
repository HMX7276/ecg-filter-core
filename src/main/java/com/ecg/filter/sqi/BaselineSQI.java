package com.ecg.filter.sqi;

/**
 * 基线漂移指数（bSQI）。
 *
 * <p>定义：0-0.5 Hz 频段能量占总能量的比例。
 * 该频段对应呼吸引起的基线漂移，占比越大说明基线漂移越严重。</p>
 *
 * <p>实现：用 IIR 低通滤波器（2 阶 Butterworth，0.5 Hz）提取低频成分，
 * 计算低频能量与总能量的比值。量纲一致（均为时域平方和），输出范围 [0, 1]。</p>
 */
public class BaselineSQI {

    // 2 阶 Butterworth 低通 0.5 Hz @ 250 Hz（由 scipy.signal.butter 生成）
    private static final double[] B = {
            3.9130205399144341e-05, 7.8260410798288682e-05, 3.9130205399144341e-05};
    private static final double[] A = {
            1.0000000000000000e+00, -1.9822289297925284e+00, 9.8238545061412508e-01};

    /**
     * 计算基线漂移指数。
     *
     * @param signal       输入信号
     * @param samplingRate 采样率（Hz，当前仅支持 250）
     * @return bSQI，范围 [0, 1]
     */
    public double compute(double[] signal, double samplingRate) {
        if (samplingRate != 250) throw new IllegalArgumentException("Only 250 Hz is supported");
        com.ecg.filter.butterworth.SosZeroPhaseFilter.validateSignal(signal,1);
        double total = totalEnergy(signal);
        if (total < 1e-12) {
            return 0.0;
        }
        double[] low = lfilter(B, A, signal);
        double lowEnergy = totalEnergy(low);
        return clamp01(lowEnergy / total);
    }

    static double[] lfilter(double[] b, double[] a, double[] x) {
        int n = x.length;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = b[0] * x[i];
            for (int j = 1; j < b.length && j <= i; j++) {
                sum += b[j] * x[i - j];
            }
            for (int j = 1; j < a.length && j <= i; j++) {
                sum -= a[j] * y[i - j];
            }
            y[i] = sum;
        }
        return y;
    }

    static double totalEnergy(double[] x) {
        double sum = 0.0;
        for (double v : x) {
            sum += v * v;
        }
        return sum;
    }

    static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
