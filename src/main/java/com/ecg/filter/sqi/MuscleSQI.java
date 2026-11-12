package com.ecg.filter.sqi;

/**
 * 高频噪声指数（mSQI）。
 *
 * <p>定义：&gt;35 Hz 频段能量占总能量的比例。
 * mSQI 对 &gt;35Hz 频段能量敏感，可同时响应<b>肌电噪声（EMG）</b>和
 * <b>工频干扰（powerline interference，如 50Hz）</b>；其阈值以 50Hz 工频为标定基准。</p>
 *
 * <p>实现：用 IIR 高通滤波器（2 阶 Butterworth，35 Hz）提取高频成分，
 * 计算高频能量与总能量的比值。量纲一致，输出范围 [0, 1]。</p>
 */
public class MuscleSQI {

    // 2 阶 Butterworth 高通 35 Hz @ 250 Hz（由 scipy.signal.butter 生成）
    private static final double[] B = {
            5.2996722706934807e-01, -1.0599344541386961e+00, 5.2996722706934807e-01};
    private static final double[] A = {
            1.0000000000000000e+00, -8.2523238068947835e-01, 2.9463652758791470e-01};

    /**
     * 计算高频噪声指数（可响应肌电噪声和工频干扰）。
     *
     * @param signal       输入信号
     * @param samplingRate 采样率（Hz，当前仅支持 250）
     * @return mSQI，范围 [0, 1]
     */
    public double compute(double[] signal, double samplingRate) {
        if (samplingRate != 250) throw new IllegalArgumentException("Only 250 Hz is supported");
        com.ecg.filter.butterworth.SosZeroPhaseFilter.validateSignal(signal,1);
        double total = BaselineSQI.totalEnergy(signal);
        if (total < 1e-12) {
            return 0.0;
        }
        double[] high = BaselineSQI.lfilter(B, A, signal);
        double highEnergy = BaselineSQI.totalEnergy(high);
        return BaselineSQI.clamp01(highEnergy / total);
    }
}
