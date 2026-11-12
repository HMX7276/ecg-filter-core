package com.ecg.filter.sqi;

import com.ecg.filter.model.SQIResult;

/**
 * 信号质量指数（SQI）计算门面。
 *
 * <p>组合 {@link BaselineSQI}、{@link MuscleSQI}、{@link KurtosisSQI} 三个指标，
 * 一次性计算并返回 {@link SQIResult}。</p>
 *
 * <p>无状态，线程安全。</p>
 */
public class SQIComputer {

    private final BaselineSQI baselineSQI;
    private final MuscleSQI muscleSQI;
    private final KurtosisSQI kurtosisSQI;

    public SQIComputer() {
        this.baselineSQI = new BaselineSQI();
        this.muscleSQI = new MuscleSQI();
        this.kurtosisSQI = new KurtosisSQI();
    }

    /**
     * 计算信号质量指数。
     *
     * @param signal       输入信号
     * @param samplingRate 采样率（Hz）
     * @return 包含 bSQI、mSQI、kSQI 的结果
     */
    public SQIResult compute(double[] signal, double samplingRate) {
        double b = baselineSQI.compute(signal, samplingRate);
        double m = muscleSQI.compute(signal, samplingRate);
        double k = kurtosisSQI.compute(signal);
        return new SQIResult(b, m, k);
    }
}
