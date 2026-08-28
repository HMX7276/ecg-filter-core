package com.ecg.filter.adaptive;

import com.ecg.filter.butterworth.ZeroPhaseButterworthFilter;
import com.ecg.filter.model.SQIResult;
import com.ecg.filter.sqi.SQIComputer;

/**
 * 自适应零相位滤波器门面。
 *
 * <p>将信号质量评估（SQI）、配置选择（Selector）、零相位滤波（Butterworth）串接起来，
 * 实现"先评估噪声 → 再选择配置 → 最后滤波"的自适应流程。</p>
 *
 * <p>默认按整段信号处理；如需分窗（如 10 秒窗口）逐帧处理，
 * 可设置窗口大小后由 {@link #filter(double[], int)} 内部自动分窗。</p>
 */
public class AdaptiveZeroPhaseFilter {

    private final ZeroPhaseButterworthFilter filter;
    private final AdaptiveFilterSelector selector;
    private final SQIComputer sqiComputer;

    /** 窗口大小（秒），默认 10 秒；0 表示不分窗，整段处理 */
    private double windowSeconds = 10.0;

    public AdaptiveZeroPhaseFilter() {
        this.filter = new ZeroPhaseButterworthFilter();
        this.selector = new AdaptiveFilterSelector();
        this.sqiComputer = new SQIComputer();
    }

    /**
     * 自适应滤波：先计算 SQI，再选择配置，最后零相位滤波。
     *
     * @param signal       输入信号
     * @param samplingRate 采样率（Hz）
     * @return 滤波后信号（长度与输入相同）
     */
    public double[] filter(double[] signal, int samplingRate) {
        if (windowSeconds <= 0) {
            return filterSingle(signal, samplingRate);
        }
        return filterWithWindows(signal, samplingRate);
    }

    /** 整段信号自适应滤波（不分窗） */
    private double[] filterSingle(double[] signal, int samplingRate) {
        SQIResult sqi = sqiComputer.compute(signal, samplingRate);
        FilterConfig config = selector.select(sqi.bSQI, sqi.mSQI);
        return filter.filter(signal, config);
    }

    /** 分窗自适应滤波（按窗口逐帧选择配置） */
    private double[] filterWithWindows(double[] signal, int samplingRate) {
        int windowSize = (int) (windowSeconds * samplingRate);
        if (windowSize >= signal.length) {
            return filterSingle(signal, samplingRate);
        }

        double[] result = new double[signal.length];
        int start = 0;
        while (start < signal.length) {
            int end = Math.min(start + windowSize, signal.length);
            double[] segment = new double[end - start];
            System.arraycopy(signal, start, segment, 0, segment.length);

            SQIResult sqi = sqiComputer.compute(segment, samplingRate);
            FilterConfig config = selector.select(sqi.bSQI, sqi.mSQI);
            double[] filtered = filter.filter(segment, config);

            System.arraycopy(filtered, 0, result, start, filtered.length);
            start = end;
        }
        return result;
    }

    /** 设置窗口大小（秒），0 表示不分窗 */
    public void setWindowSeconds(double windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public AdaptiveFilterSelector getSelector() {
        return selector;
    }

    public SQIComputer getSqiComputer() {
        return sqiComputer;
    }
}
