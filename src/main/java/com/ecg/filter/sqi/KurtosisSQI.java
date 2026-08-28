package com.ecg.filter.sqi;

/**
 * 峰度指数（kSQI）。
 *
 * <p>定义：信号的四阶中心矩与方差平方之比（即标准峰度，未减 3）。</p>
 *
 * <pre>
 *   kSQI = E[(x - μ)^4] / (E[(x - μ)^2])^2
 * </pre>
 *
 * <p>含义：</p>
 * <ul>
 *   <li>正态分布峰度 = 3</li>
 *   <li>正常 ECG 的峰度 &gt; 3（R 波为尖峰，使分布更"尖峭"）</li>
 *   <li>噪声增大时，信号趋近正态分布，峰度趋近 3</li>
 * </ul>
 *
 * <p>输出范围：通常 [1, ∞)，正常 ECG 约 &gt; 3。</p>
 */
public class KurtosisSQI {

    /**
     * 计算峰度指数。
     *
     * @param signal 输入信号
     * @return kSQI（标准峰度，未减 3）
     */
    public double compute(double[] signal) {
        int n = signal.length;
        if (n < 4) {
            return 0.0;
        }

        double mean = 0.0;
        for (double x : signal) {
            mean += x;
        }
        mean /= n;

        double m2 = 0.0; // 二阶中心矩（方差）
        double m4 = 0.0; // 四阶中心矩
        for (double x : signal) {
            double d = x - mean;
            double d2 = d * d;
            m2 += d2;
            m4 += d2 * d2;
        }
        m2 /= n;
        m4 /= n;

        if (m2 < 1e-12) {
            return 0.0; // 常数信号，无意义
        }
        return m4 / (m2 * m2);
    }
}
