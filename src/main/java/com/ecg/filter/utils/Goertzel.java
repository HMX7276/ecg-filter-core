package com.ecg.filter.utils;

/**
 * Goertzel 算法：计算信号在特定频率的功率，用于估算频段能量。
 *
 * <p>相比完整 FFT，Goertzel 算法只需 O(n) 计算单个频点的功率，
 * 适用于 SQI 计算中"仅需少数几个频段能量"的场景。自实现，无 FFT 库依赖。</p>
 *
 * <p>精度要求不高（仅需保证频段能量相对排序正确），
 * 因此不追求与 FFT 精确一致。</p>
 */
public final class Goertzel {

    private Goertzel() {
    }

    /**
     * 计算信号在指定频率的功率。
     *
     * <p>使用 Goertzel 算法计算 DFT 在频率 {@code frequency} 处的幅度平方。</p>
     *
     * @param signal       输入信号
     * @param frequency    目标频率（Hz）
     * @param samplingRate 采样率（Hz）
     * @return 该频率处的功率（相对值，未归一化）
     */
    public static double powerAtFrequency(double[] signal, double frequency, double samplingRate) {
        int n = signal.length;
        if (n < 2) {
            return 0.0;
        }

        double normalized = frequency / samplingRate; // 归一化频率 [0, 0.5]
        double omega = 2.0 * Math.PI * normalized;
        double coeff = 2.0 * Math.cos(omega);

        double sPrev2 = 0.0;
        double sPrev1 = 0.0;
        for (double x : signal) {
            double s = x + coeff * sPrev1 - sPrev2;
            sPrev2 = sPrev1;
            sPrev1 = s;
        }

        return sPrev1 * sPrev1 + sPrev2 * sPrev2 - coeff * sPrev1 * sPrev2;
    }

    /**
     * 计算信号在指定频段 [fLow, fHigh] 内的总能量（在频段内均匀采样多个频点累加）。
     *
     * @param signal       输入信号
     * @param fLow         频段下限（Hz）
     * @param fHigh        频段上限（Hz）
     * @param samplingRate 采样率（Hz）
     * @param numPoints    频段内采样点数（默认建议 5-10）
     * @return 频段能量（相对值）
     */
    public static double bandEnergy(double[] signal, double fLow, double fHigh,
                                    double samplingRate, int numPoints) {
        if (numPoints <= 0) {
            numPoints = 5;
        }
        double total = 0.0;
        for (int i = 0; i < numPoints; i++) {
            double f = fLow + (fHigh - fLow) * (i + 0.5) / numPoints;
            total += powerAtFrequency(signal, f, samplingRate);
        }
        return total;
    }

    /**
     * 计算信号的总能量（时域平方和）。
     *
     * @param signal 输入信号
     * @return 总能量（sum(x^2)）
     */
    public static double totalEnergy(double[] signal) {
        double sum = 0.0;
        for (double x : signal) {
            sum += x * x;
        }
        return sum;
    }
}
