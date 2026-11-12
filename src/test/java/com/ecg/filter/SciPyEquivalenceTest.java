package com.ecg.filter;

import com.ecg.filter.adaptive.FilterConfig;
import com.ecg.filter.butterworth.ZeroPhaseButterworthFilter;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legacy direct-form regression on the stored synthetic reference fixture.
 * Tolerances apply to this fixture, not an intrinsic accuracy floor or a
 * guarantee of clinical equivalence. New SOS checks use independent SciPy data.
 */
public class SciPyEquivalenceTest {

    private final ZeroPhaseButterworthFilter filter = new ZeroPhaseButterworthFilter();

    /**
     * 高通滤波等价性：阈值 1e-2（0.05 Hz 低截止高通下浮点舍入放大）。
     */
    @Test
    void testHighPassEquivalence() throws Exception {
        TestData data = loadReference();
        double[] filtered = filter.filterHighPass(data.signal, FilterConfig.CONFIG_DEFAULT);
        double rmse = computeRmse(filtered, data.refHp);
        assertTrue(rmse < 1e-2,
                "高通滤波与 scipy.signal.filtfilt 的 RMSE = " + rmse
                        + " 应 < 1e-2（0.05Hz 低截止下浮点舍入放大，相对误差 ~1e-6）");
    }

    /**
     * 带通滤波等价性：阈值 1e-2（同上）。
     */
    @Test
    void testBandPassEquivalence() throws Exception {
        TestData data = loadReference();
        double[] filtered = filter.filter(data.signal, FilterConfig.CONFIG_DEFAULT);
        double rmse = computeRmse(filtered, data.refBp);
        assertTrue(rmse < 1e-2,
                "带通滤波与 scipy.signal.filtfilt 的 RMSE = " + rmse
                        + " 应 < 1e-2（浮点舍入放大，相对误差 ~1e-6）");
    }

    /**
     * lfilterZi（运行时计算）与硬编码 ZI 的一致性。
     *
     * <p>阈值 1e-3：lfilterZi 求解的矩阵（I - companion(a).T）对接近单位圆的
     * 极点接近奇异，高斯消元（非 LAPACK 迭代精化）的精度约 1e-4 量级。
     * 该差异不影响实际滤波——filtfilt 使用的是硬编码的精确 ZI 值。</p>
     */
    @Test
    void testLfilterZiMatchesHardcoded() {
        double[] computed = filter.lfilterZi(
                FilterConfig.CONFIG_DEFAULT.bHigh, FilterConfig.CONFIG_DEFAULT.aHigh);
        double[] hardcoded = FilterConfig.CONFIG_DEFAULT.ziHigh;
        double maxDiff = 0.0;
        for (int i = 0; i < computed.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(computed[i] - hardcoded[i]));
        }
        assertTrue(maxDiff < 1e-3,
                "lfilterZi 计算值与硬编码 ZI 的最大差 = " + maxDiff
                        + " 应 < 1e-3（接近奇异矩阵的高斯消元精度）");
    }

    // ================================================================
    //  辅助
    // ================================================================

    private static class TestData {
        double[] signal;
        double[] refHp;
        double[] refBp;
    }

    private TestData loadReference() throws Exception {
        List<Double> signal = new ArrayList<>();
        List<Double> refHp = new ArrayList<>();
        List<Double> refBp = new ArrayList<>();

        try (InputStream is = getClass().getResourceAsStream("/scipy_reference.csv");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line = br.readLine(); // 跳过表头
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                signal.add(Double.parseDouble(parts[0]));
                refHp.add(Double.parseDouble(parts[1]));
                refBp.add(Double.parseDouble(parts[2]));
            }
        }

        TestData data = new TestData();
        data.signal = toArray(signal);
        data.refHp = toArray(refHp);
        data.refBp = toArray(refBp);
        return data;
    }

    private double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private double computeRmse(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum / n);
    }
}
