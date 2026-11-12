package com.ecg.filter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.ecg.filter.adaptive.*;
import com.ecg.filter.butterworth.*;
import com.ecg.filter.streaming.*;

public class RevisionContractTest {
    @Test void calibrationAndHysteresis() {
        SessionCalibratedSelector s=new SessionCalibratedSelector(3,3);
        for(int i=0;i<3;i++) assertEquals(FilterConfig.CONFIG_DEFAULT,s.select(10,0.01));
        assertTrue(s.isCalibrated()); assertEquals(10,s.getBaselineThreshold()); assertEquals(.05,s.getMuscleThreshold());
        assertEquals(FilterConfig.CONFIG_DEFAULT,s.select(100,.01));
        assertEquals(FilterConfig.CONFIG_DEFAULT,s.select(100,.01));
        assertEquals(FilterConfig.CONFIG_BASELINE_HEAVY,s.select(100,.01));
        assertEquals(FilterConfig.CONFIG_DEFAULT,s.decide(10,.05));
    }
    @Test void inputAndLifecycleContracts() {
        SosZeroPhaseFilter f=new SosZeroPhaseFilter();
        assertThrows(IllegalArgumentException.class,()->f.filter(new double[15],FilterConfig.CONFIG_DEFAULT));
        assertThrows(IllegalArgumentException.class,()->f.filter(new double[]{Double.NaN},FilterConfig.CONFIG_DEFAULT));
        OverlapAddStream stream=new OverlapAddStream(FilterConfig.CONFIG_DEFAULT);
        assertEquals(0,stream.accept(new double[2499]).length);
        assertEquals(1250,stream.accept(new double[1]).length);
        assertEquals(1250,stream.finish().length);
        assertThrows(IllegalStateException.class,()->stream.accept(new double[1]));
    }
}
