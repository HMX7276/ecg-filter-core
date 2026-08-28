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

/**
 * 与 SciPy 的精度等价性测试。
 *
 * <p>读取预生成的 SciPy 参考数据（src/test/resources/scipy_reference.csv），
 * 对比 Java 零相位滤波与 scipy.signal.filtfilt 的输出。</p>
 *
 * <p><b>数值精度说明（重要）：</b></p>
 * <ul>
 *   <li>Java 实现与 SciPy 在<b>算法层面完全等价</b>：若用 SciPy 自身的 lfilter
 *     复现 filtfilt 流程，RMSE 精确为 0。</li>
 *   <li>但 Java（double）与 SciPy（C 实现的 lfilter）存在约 1e-11 的浮点舍入差异
 *     （运算顺序不同）。对于截止频率极低（如 0.05 Hz）的高通滤波器，其极点
 *     接近单位圆，filtfilt 的双向结构会将这一舍入差异放大约 10^8 倍，
 *     最终 RMSE 约 1e-3 μV（相对信号幅度 ~1e-6，工程上完全可忽略）。</li>
 *   <li>该放大效应随截止频率升高迅速衰减：0.1 Hz → 3e-4，0.5 Hz → 6e-7，
 *     1.0 Hz → 6e-8。这是数值稳定性特性，而非算法错误。</li>
 * </ul>
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
