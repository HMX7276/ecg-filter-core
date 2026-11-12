package com.ecg.filter;

import com.ecg.filter.adaptive.AdaptiveFilterSelector;
import com.ecg.filter.adaptive.FilterConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自适应配置选择器测试。
 *
 * <p>验证基于 bSQI/mSQI 的配置切换逻辑，以及滞后机制（连续 3 帧才切换）。</p>
 */
public class AdaptiveSelectorTest {

    @Test
    void testBaselineHeavySelection() {
        AdaptiveFilterSelector selector = new AdaptiveFilterSelector();
        // 直接决策：bSQI 高、mSQI 低 → 基线漂移严重
        assertEquals(FilterConfig.CONFIG_BASELINE_HEAVY, selector.decide(0.10, 0.01));
    }

    @Test
    void testMuscleHeavySelection() {
        AdaptiveFilterSelector selector = new AdaptiveFilterSelector();
        assertEquals(FilterConfig.CONFIG_MUSCLE_HEAVY, selector.decide(0.01, 0.10));
    }

    @Test
    void testConservativeSelection() {
        AdaptiveFilterSelector selector = new AdaptiveFilterSelector();
        assertEquals(FilterConfig.CONFIG_CONSERVATIVE, selector.decide(0.10, 0.10));
    }

    @Test
    void testDefaultSelection() {
        AdaptiveFilterSelector selector = new AdaptiveFilterSelector();
        assertEquals(FilterConfig.CONFIG_DEFAULT, selector.decide(0.01, 0.01));
    }

    @Test
    void testHysteresisRequiresThreeConsecutiveFrames() {
        AdaptiveFilterSelector selector = new AdaptiveFilterSelector();

        // 第 1 帧：目标变为 BASELINE_HEAVY，但滞后计数=1，尚未切换
        assertEquals(FilterConfig.CONFIG_DEFAULT, selector.select(0.10, 0.01));
        // 第 2 帧：滞后计数=2，仍未切换
        assertEquals(FilterConfig.CONFIG_DEFAULT, selector.select(0.10, 0.01));
        // 第 3 帧：滞后计数=3，正式切换
        assertEquals(FilterConfig.CONFIG_BASELINE_HEAVY, selector.select(0.10, 0.01));
    }

    @Test
    void testHysteresisResetsOnDifferentTarget() {
        AdaptiveFilterSelector selector = new AdaptiveFilterSelector();

        // 连续 2 帧基线漂移
        selector.select(0.10, 0.01);
        selector.select(0.10, 0.01);
        // 第 3 帧突然变为肌电噪声（目标改变），滞后计数复位
        assertEquals(FilterConfig.CONFIG_DEFAULT, selector.select(0.01, 0.10));
    }
}
