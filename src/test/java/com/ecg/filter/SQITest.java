package com.ecg.filter;

import com.ecg.filter.model.SQIResult;
import com.ecg.filter.sqi.SQIComputer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQI（信号质量指数）测试。
 *
 * <p>构造已知噪声类型的合成信号，验证 bSQI/mSQI 能正确识别噪声类型
 * （相对排序正确，而非绝对精度）。</p>
 */
public class SQITest {

    private static final double FS = 250.0;
    private static final int N = 5000; // 20 秒

    private final SQIComputer sqiComputer = new SQIComputer();

    @Test
    void testBaselineSqiDetectsBaselineDrift() {
        double[] clean = cleanEcg();
        double[] baselineHeavy = addBaselineDrift(clean);

        SQIResult cleanSqi = sqiComputer.compute(clean, FS);
        SQIResult heavySqi = sqiComputer.compute(baselineHeavy, FS);

        assertTrue(heavySqi.bSQI > cleanSqi.bSQI,
                "基线漂移信号的 bSQI(" + heavySqi.bSQI + ") 应大于干净信号(" + cleanSqi.bSQI + ")");
    }

    @Test
    void testMuscleSqiDetectsMuscleNoise() {
        double[] clean = cleanEcg();
        double[] muscleHeavy = addMuscleNoise(clean);

        SQIResult cleanSqi = sqiComputer.compute(clean, FS);
        SQIResult heavySqi = sqiComputer.compute(muscleHeavy, FS);

        assertTrue(heavySqi.mSQI > cleanSqi.mSQI,
                "肌电噪声信号的 mSQI(" + heavySqi.mSQI + ") 应大于干净信号(" + cleanSqi.mSQI + ")");
    }

    @Test
    void testKurtosisOfCleanEcgExceedsGaussian() {
        double[] clean = cleanEcg();
        SQIResult sqi = sqiComputer.compute(clean, FS);
        // 正常 ECG 的峰度 > 3（R 波尖峰使分布比高斯更尖峭）
        assertTrue(sqi.kSQI > 3.0,
                "干净 ECG 的峰度 kSQI(" + sqi.kSQI + ") 应 > 3.0");
    }

    @Test
    void testNoiseReducesKurtosis() {
        double[] clean = cleanEcg();
        double[] noisy = addWhiteNoise(clean);

        SQIResult cleanSqi = sqiComputer.compute(clean, FS);
        SQIResult noisySqi = sqiComputer.compute(noisy, FS);

        // 白噪声使信号趋近高斯分布，峰度降低
        assertTrue(noisySqi.kSQI < cleanSqi.kSQI,
                "噪声信号的峰度(" + noisySqi.kSQI + ") 应低于干净信号(" + cleanSqi.kSQI + ")");
    }

    // ================================================================
    //  合成信号构造
    // ================================================================

    private double[] cleanEcg() {
        double[] signal = new double[N];
        for (int i = 0; i < N; i++) {
            double t = i / FS;
            double phase = (t * 1.2) % 1.0; // 72 bpm
            signal[i] = 1500.0 * Math.exp(-Math.pow((phase - 0.1) / 0.02, 2))  // R 波
                      + 300.0 * Math.exp(-Math.pow((phase - 0.4) / 0.04, 2));  // T 波
        }
        return signal;
    }

    private double[] addBaselineDrift(double[] clean) {
        double[] result = new double[clean.length];
        for (int i = 0; i < clean.length; i++) {
            double t = i / FS;
            result[i] = clean[i] + 500.0 * Math.sin(2 * Math.PI * 0.2 * t);
        }
        return result;
    }

    private double[] addMuscleNoise(double[] clean) {
        double[] result = new double[clean.length];
        for (int i = 0; i < clean.length; i++) {
            double t = i / FS;
            result[i] = clean[i] + 200.0 * Math.sin(2 * Math.PI * 50.0 * t);
        }
        return result;
    }

    private double[] addWhiteNoise(double[] clean) {
        java.util.Random rng = new java.util.Random(42);
        double[] result = new double[clean.length];
        for (int i = 0; i < clean.length; i++) {
            result[i] = clean[i] + rng.nextGaussian() * 300.0;
        }
        return result;
    }
}
