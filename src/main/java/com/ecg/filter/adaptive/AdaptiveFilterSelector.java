package com.ecg.filter.adaptive;

/**
 * Legacy fixed-bSQI selector; retained only for historical comparisons.
 * Use SessionCalibratedSelector with BWA and centered mSQI for the revision.
 *
 * <p>基于信号质量指数（bSQI、mSQI）选择最合适的滤波配置，
 * 内置滞后机制：连续 {@value #HYSTERESIS_FRAMES} 帧满足同一切换条件才真正切换，
 * 防止噪声导致的配置闪烁（频繁切换）。</p>
 *
 * <p>Historical thresholds below are not the revised deployment-domain calibration.</p>
 * <pre>
 *   if (bSQI &gt; 0.30 &amp;&amp; mSQI &lt; 0.10) → CONFIG_BASELINE_HEAVY   (基线漂移严重)
 *   if (bSQI &lt; 0.10 &amp;&amp; mSQI &gt; 0.15) → CONFIG_MUSCLE_HEAVY     (肌电噪声大)
 *   if (bSQI &gt; 0.30 &amp;&amp; mSQI &gt; 0.15) → CONFIG_CONSERVATIVE    (综合噪声大)
 *   否则                                    → CONFIG_DEFAULT        (正常)
 * </pre>
 *
 * <p>线程安全：调用方需自行同步（通常单线程处理一帧数据）。</p>
 */
@Deprecated
public class AdaptiveFilterSelector {

    /** 滞后帧数：连续满足同一条件达到此帧数才切换 */
    private static final int HYSTERESIS_FRAMES = 3;

    /**
     * 基线漂移阈值（bSQI）。
     * 标定依据：干净信号（带通滤波后）bSQI 的 95% 分位数（0.0266）。
     * Original calibration script was not present in the upstream snapshot.
     */
    private static final double BASELINE_THRESHOLD = 0.0266;

    /**
     * 高频噪声阈值（mSQI）。
     * <p>mSQI 对 &gt;35Hz 频段能量敏感，可同时响应肌电噪声（EMG）和
     * 工频干扰（powerline interference）。</p>
     * <p>标定依据：干净信号（带通滤波后）mSQI 的 95% 分位数（0.0404），
     * 以 50Hz 工频为标定基准（工频干扰使 mSQI 升至 ~0.43，远高于阈值）。</p>
     */
    private static final double MUSCLE_THRESHOLD = 0.0404;

    /** 当前生效的配置 */
    private FilterConfig currentConfig = FilterConfig.CONFIG_DEFAULT;

    /** 待切换的目标配置 */
    private FilterConfig pendingConfig = FilterConfig.CONFIG_DEFAULT;

    /** 待切换配置已连续满足条件的帧数 */
    private int pendingCount = 0;

    /**
     * 根据信号质量指数选择滤波配置。
     *
     * @param bSQI 基线漂移指数（0-0.5Hz 能量占比）
     * @param mSQI 高频噪声指数（&gt;35Hz 能量占比，可响应肌电噪声和工频干扰）
     * @return 当前应使用的滤波配置
     */
    public FilterConfig select(double bSQI, double mSQI) {
        FilterConfig target = decide(bSQI, mSQI);

        // 目标与当前一致：立即复位滞后计数
        if (target == currentConfig) {
            pendingConfig = currentConfig;
            pendingCount = 0;
            return currentConfig;
        }

        // 目标与待切换一致：累加滞后计数
        if (target == pendingConfig) {
            pendingCount++;
        } else {
            // 目标变化：重新开始计数
            pendingConfig = target;
            pendingCount = 1;
        }

        // 达到滞后帧数：正式切换
        if (pendingCount >= HYSTERESIS_FRAMES) {
            currentConfig = pendingConfig;
            pendingCount = 0;
        }

        return currentConfig;
    }

    /** 重置选择器状态 */
    public void reset() {
        currentConfig = FilterConfig.CONFIG_DEFAULT;
        pendingConfig = FilterConfig.CONFIG_DEFAULT;
        pendingCount = 0;
    }

    /** 获取当前生效配置 */
    public FilterConfig getCurrentConfig() {
        return currentConfig;
    }

    /** 纯决策逻辑（无滞后），供测试和外部调用 */
    public FilterConfig decide(double bSQI, double mSQI) {
        if (bSQI > BASELINE_THRESHOLD && mSQI < MUSCLE_THRESHOLD) {
            return FilterConfig.CONFIG_BASELINE_HEAVY;
        }
        if (bSQI < BASELINE_THRESHOLD && mSQI > MUSCLE_THRESHOLD) {
            return FilterConfig.CONFIG_MUSCLE_HEAVY;
        }
        if (bSQI > BASELINE_THRESHOLD && mSQI > MUSCLE_THRESHOLD) {
            return FilterConfig.CONFIG_CONSERVATIVE;
        }
        return FilterConfig.CONFIG_DEFAULT;
    }
}
