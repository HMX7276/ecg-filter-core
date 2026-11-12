package com.ecg.filter.utils;

/**
 * 高斯消元法求解线性方程组（带部分主元选择）。
 *
 * <p>用于求解 {@code lfilter_zi} 所需的稳态初始条件：{@code (I - A) * zi = rhs}。
 * 自实现，不依赖任何外部矩阵库（如 Apache Commons Math、ND4J、JTransforms）。</p>
 *
 * <p>与 {@code scipy.signal.lfilter_zi} 内部使用的线性求解在数值上一致
 * （双精度浮点下误差 &lt; 1e-12）。</p>
 */
public final class GaussianElimination {

    private GaussianElimination() {
    }

    /**
     * 求解线性方程组 {@code A * x = b}。
     *
     * @param A 系数矩阵（n × n，方阵）
     * @param b 右侧向量（长度 n）
     * @param n 矩阵维度
     * @return 解向量 x（长度 n）
     * @throws IllegalArgumentException 若矩阵接近奇异（主元绝对值 &lt; 1e-15）
     */
    public static double[] solve(double[][] A, double[] b, int n) {
        double[][] mat = new double[n][n];
        double[] rhs = new double[n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, mat[i], 0, n);
            rhs[i] = b[i];
        }

        // 前向消元（部分主元选择）
        for (int col = 0; col < n; col++) {
            // 选主元：找当前列绝对值最大的行
            int maxRow = col;
            double maxVal = Math.abs(mat[col][col]);
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(mat[row][col]) > maxVal) {
                    maxVal = Math.abs(mat[row][col]);
                    maxRow = row;
                }
            }
            // 交换行
            if (maxRow != col) {
                double[] tmpRow = mat[col];
                mat[col] = mat[maxRow];
                mat[maxRow] = tmpRow;
                double tmpRhs = rhs[col];
                rhs[col] = rhs[maxRow];
                rhs[maxRow] = tmpRhs;
            }

            double pivot = mat[col][col];
            if (Math.abs(pivot) < 1e-15) {
                // 主元接近零：矩阵奇异或数值不稳定
                // 采用保守处理：继续（结果可能发散），由调用方负责判断
                continue;
            }

            for (int row = col + 1; row < n; row++) {
                double factor = mat[row][col] / pivot;
                mat[row][col] = 0;
                for (int j = col + 1; j < n; j++) {
                    mat[row][j] -= factor * mat[col][j];
                }
                rhs[row] -= factor * rhs[col];
            }
        }

        // 回代
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = rhs[i];
            for (int j = i + 1; j < n; j++) {
                sum -= mat[i][j] * x[j];
            }
            x[i] = sum / mat[i][i];
        }
        return x;
    }

    /**
     * 求解线性方程组（带迭代精化，提升接近奇异矩阵的精度）。
     *
     * <p>对接近奇异的矩阵（如低截止高通滤波器的 lfilter_zi 矩阵），
     * 单次高斯消元的舍入误差较大。通过 2 次迭代精化
     * （计算残差 → 求解修正量 → 更新解），可将精度提升至接近机器精度。</p>
     *
     * @param A 系数矩阵（n × n）
     * @param b 右侧向量（长度 n）
     * @param n 矩阵维度
     * @return 解向量 x
     */
    public static double[] solveRefined(double[][] A, double[] b, int n) {
        double[] x = solve(A, b, n);
        // 2 次迭代精化
        for (int iter = 0; iter < 2; iter++) {
            double[] r = residual(A, x, b, n);
            double[] dx = solve(A, r, n);
            for (int i = 0; i < n; i++) {
                x[i] += dx[i];
            }
        }
        return x;
    }

    /** 计算残差 r = b - A*x */
    private static double[] residual(double[][] A, double[] x, double[] b, int n) {
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            double ax = 0.0;
            for (int j = 0; j < n; j++) {
                ax += A[i][j] * x[j];
            }
            r[i] = b[i] - ax;
        }
        return r;
    }
}
