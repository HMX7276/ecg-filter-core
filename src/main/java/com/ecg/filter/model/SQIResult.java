package com.ecg.filter.model;

/**
 * 信号质量指数（SQI）计算结果。
 *
 * <p>包含三个时域/频域统计指标，用于评估 ECG 信号的噪声类型和程度：</p>
 * <ul>
 *   <li>{@link #bSQI} 基线漂移指数（0-0.5Hz 频段能量占比，越大基线漂移越严重）</li>
 *   <li>{@link #mSQI} 高频噪声指数（&gt;35Hz 频段能量占比，可响应肌电噪声和工频干扰，阈值以 50Hz 工频为标定基准）</li>
 *   <li>{@link #kSQI} 峰度指数（正常 ECG 约 &gt;3，噪声大时降低）</li>
 * </ul>
 *
 * <p>不可变，线程安全。</p>
 */
public class SQIResult {

    /** 基线漂移指数，范围 [0, 1] */
    public final double bSQI;

    /** 高频噪声指数（可响应肌电噪声和工频干扰），范围 [0, 1] */
    public final double mSQI;

    /** 峰度指数（无量纲） */
    public final double kSQI;

    public SQIResult(double bSQI, double mSQI, double kSQI) {
        this.bSQI = bSQI;
        this.mSQI = mSQI;
        this.kSQI = kSQI;
    }

    @Override
    public String toString() {
        return String.format("SQIResult{bSQI=%.4f, mSQI=%.4f, kSQI=%.2f}", bSQI, mSQI, kSQI);
    }
}
