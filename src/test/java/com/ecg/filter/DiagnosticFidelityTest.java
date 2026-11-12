package com.ecg.filter;

import org.junit.jupiter.api.Test;
import com.ecg.filter.butterworth.SosZeroPhaseFilter;
import com.ecg.filter.adaptive.FilterConfig;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Basic execution contract only; clinical fidelity is not established by this test. */
public class DiagnosticFidelityTest {

    /**
     * 预留测试：验证滤波后信号长度契约。
     * 后续替换为真实的 QRS 宽度变化 / ST 段偏移断言。
     */
    @Test
    void testFilterPreservesLength() {
        double[] signal = new double[2500]; // 10 秒 @ 250Hz
        for (int i = 0; i < signal.length; i++) {
            signal[i] = Math.sin(2 * Math.PI * 1.0 * i / 250.0) * 1000.0;
        }
        double[] actual = new SosZeroPhaseFilter().filter(signal,FilterConfig.CONFIG_DEFAULT);
        assertTrue(actual.length == signal.length, "Signal length contract; not clinical validation");
        for(double value:actual) assertTrue(Double.isFinite(value));
    }
}
