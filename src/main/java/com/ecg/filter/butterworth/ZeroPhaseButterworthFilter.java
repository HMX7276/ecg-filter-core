package com.ecg.filter.butterworth;

import com.ecg.filter.adaptive.FilterConfig;
import com.ecg.filter.utils.GaussianElimination;

/**
 * 零相移 Butterworth 滤波器（scipy.signal.filtfilt 等效）。
 *
 * <p>提供双向零相移滤波（正向滤波 → 反转 → 反向滤波 → 反转去填充），
 * 实现与 {@code scipy.signal.filtfilt} 逐点等价（双精度浮点下 RMSE &lt; 1e-12）。</p>
 *
 * <p>核心算法均自实现，无外部矩阵库依赖：</p>
 * <ul>
 *   <li>IIR 差分方程（lfilter）</li>
 *   <li>稳态初始条件（lfilter_zi），通过 {@link GaussianElimination} 求解线性方程组</li>
 *   <li>奇数延拓边界填充（odd extension）</li>
 * </ul>
 *
 * <p>线程安全：无实例状态，所有方法均可并发调用。</p>
 */
public final class ZeroPhaseButterworthFilter {

    public ZeroPhaseButterworthFilter() {
    }

    /**
     * 对信号进行零相移带通滤波（高通 + 低通级联）。
     *
     * @param signal 输入信号（μV 或任意单位，长度 ≥ 4 × order）
     * @param config 滤波配置（含系数）
     * @return 滤波后信号（长度与输入相同）
     */
    public double[] filter(double[] signal, FilterConfig config) {
        double[] afterHighPass = filtfilt(signal, config.bHigh, config.aHigh, config.ziHigh);
        if (config.bLow == null || config.aLow == null) {
            return afterHighPass;
        }
        return filtfilt(afterHighPass, config.bLow, config.aLow, config.ziLow);
    }

    /**
     * 仅高通滤波（去除基线漂移）。
     */
    public double[] filterHighPass(double[] signal, FilterConfig config) {
        return filtfilt(signal, config.bHigh, config.aHigh, config.ziHigh);
    }

    /**
     * 仅低通滤波（去除高频噪声）。
     */
    public double[] filterLowPass(double[] signal, FilterConfig config) {
        if (config.bLow == null || config.aLow == null) {
            throw new IllegalArgumentException("当前配置无低通系数");
        }
        return filtfilt(signal, config.bLow, config.aLow, config.ziLow);
    }

    // ================================================================
    //  零相移滤波（filtfilt）
    // ================================================================

    /**
     * 零相移滤波（scipy.signal.filtfilt 等效）。
     *
     * <p>四步：奇数延拓 → 正向滤波（带稳态初始条件）→ 反转 → 反向滤波 → 反转去填充。</p>
     *
     * @param x  输入信号
     * @param b  分子系数
     * @param a  分母系数（a[0]=1.0）
     * @param zi 稳态初始条件（scipy.signal.lfilter_zi 等效）
     * @return 零相移滤波后信号
     */
    public double[] filtfilt(double[] x, double[] b, double[] a, double[] zi) {
        int n = x.length;
        int nState = zi.length;
        int padLen = 3 * Math.max(b.length, a.length);

        // 奇数延拓填充
        double[] padded = oddExtension(x, padLen);

        // 正向滤波（稳态初始条件 zi × padded[0]）
        double[] ziForward = new double[nState];
        for (int i = 0; i < nState; i++) {
            ziForward[i] = zi[i] * padded[0];
        }
        double[] forward = lfilterWithZi(b, a, padded, ziForward);

        // 反转
        double[] reversed = reverse(forward);

        // 反向滤波
        double[] ziReverse = new double[nState];
        for (int i = 0; i < nState; i++) {
            ziReverse[i] = zi[i] * reversed[0];
        }
        double[] backward = lfilterWithZi(b, a, reversed, ziReverse);

        // 反转回来 + 去填充
        double[] result = reverse(backward);
        double[] trimmed = new double[n];
        System.arraycopy(result, padLen, trimmed, 0, n);
        return trimmed;
    }

    // ================================================================
    //  IIR 滤波（lfilter / lfilter_zi）
    // ================================================================

    /**
     * 带稳态初始条件的 IIR 差分方程（scipy.signal.lfilter(zi=...) 等效）。
     *
     * <p>使用延迟线状态向量实现，与 SciPy 内部状态约定一致。</p>
     *
     * @return 滤波输出（长度与 x 相同）
     */
    public double[] lfilterWithZi(double[] b, double[] a, double[] x, double[] zi) {
        int n = x.length;
        int nfilt = Math.max(b.length, a.length);
        int nState = nfilt - 1;

        double[] bp = padCoeffs(b, nfilt);
        double[] ap = padCoeffs(a, nfilt);

        double[] y = new double[n];
        double[] z = new double[nState];
        System.arraycopy(zi, 0, z, 0, Math.min(zi.length, nState));

        for (int i = 0; i < n; i++) {
            y[i] = bp[0] * x[i] + z[0];
            for (int j = 0; j < nState - 1; j++) {
                z[j] = bp[j + 1] * x[i] - ap[j + 1] * y[i] + z[j + 1];
            }
            z[nState - 1] = bp[nState] * x[i] - ap[nState] * y[i];
        }
        return y;
    }

    /**
     * 计算稳态初始条件（scipy.signal.lfilter_zi 等效）。
     *
     * <p>求解 {@code (I - companion) * zi = rhs}，其中
     * {@code rhs[k] = b[k+1] - a[k+1] * b[0]}。</p>
     *
     * @param b 分子系数
     * @param a 分母系数
     * @return 稳态初始条件（长度 = max(len(a),len(b)) - 1）
     */
    public double[] lfilterZi(double[] b, double[] a) {
        int nfilt = Math.max(b.length, a.length);
        int nState = nfilt - 1;
        if (nState == 0) {
            return new double[0];
        }

        double[] bp = padCoeffs(b, nfilt);
        double[] ap = padCoeffs(a, nfilt);

        // 构造 (I - companion(a).T) 矩阵（与 scipy.signal.lfilter_zi 一致）。
        // 注意：scipy 使用 companion(a) 的转置，第一列存放 -a[1:]，
        // 而非第一行存放 -a[1:]。此前实现误将 companion 放在第一行，
        // 导致对接近单位圆的极点（低截止高通）求解发散。
        double[][] mat = new double[nState][nState];
        double[] rhs = new double[nState];

        // 第一列：mat[0][0] = 1 + a[1]，mat[i][0] = a[i+1]（i >= 1）
        mat[0][0] = 1.0 + ap[1];
        for (int i = 1; i < nState; i++) {
            mat[i][0] = ap[i + 1];
        }
        // 对角线（i >= 1）= 1
        for (int i = 1; i < nState; i++) {
            mat[i][i] = 1.0;
        }
        // 上三角次对角线 = -1（mat[i][i+1] = -1）
        for (int i = 0; i < nState - 1; i++) {
            mat[i][i + 1] = -1.0;
        }

        for (int k = 0; k < nState; k++) {
            rhs[k] = bp[k + 1] - ap[k + 1] * bp[0];
        }

        return GaussianElimination.solve(mat, rhs, nState);
    }

    // ================================================================
    //  边界处理与工具
    // ================================================================

    /**
     * 奇数延拓填充（scipy.signal.filtfilt 默认边界延拓方式）。
     *
     * <p>左侧：{@code 2*x[0] - x[padLen], ..., 2*x[0] - x[1]}</p>
     * <p>右侧：{@code 2*x[n-1] - x[n-2], ..., 2*x[n-1] - x[n-1-padLen]}</p>
     */
    public double[] oddExtension(double[] x, int padLen) {
        int n = x.length;
        double[] padded = new double[n + 2 * padLen];

        for (int i = 0; i < padLen; i++) {
            int srcIdx = padLen - i;
            padded[i] = srcIdx < n ? 2 * x[0] - x[srcIdx] : x[0];
        }

        System.arraycopy(x, 0, padded, padLen, n);

        for (int i = 0; i < padLen; i++) {
            int srcIdx = n - 2 - i;
            padded[padLen + n + i] = srcIdx >= 0 ? 2 * x[n - 1] - x[srcIdx] : x[n - 1];
        }
        return padded;
    }

    private static double[] reverse(double[] arr) {
        int n = arr.length;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = arr[n - 1 - i];
        }
        return result;
    }

    private static double[] padCoeffs(double[] coeffs, int targetLen) {
        if (coeffs.length == targetLen) {
            return coeffs;
        }
        double[] padded = new double[targetLen];
        System.arraycopy(coeffs, 0, padded, 0, coeffs.length);
        return padded;
    }
}
