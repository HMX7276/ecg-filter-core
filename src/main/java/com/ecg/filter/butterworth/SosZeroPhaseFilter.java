package com.ecg.filter.butterworth;

import com.ecg.filter.adaptive.FilterConfig;

/**
 * Cascaded biquad forward-backward filtering with odd padding and step-steady
 * initial states. Corresponds to SciPy sosfiltfilt's padding method, NOT Gustafsson.
 * Coefficients are designed offline; no external runtime dependencies.
 */
public final class SosZeroPhaseFilter {
    public double[] filter(double[] x, FilterConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        // Separate HP and LP passes preserve the original pipeline's edge convention.
        return sosfiltfilt(sosfiltfilt(x, SosCoefficients.high(config)), SosCoefficients.low(config));
    }

    public double[] sosfiltfilt(double[] x, double[][] coefficients) {
        double[][] sos = normalized(coefficients);
        int bZero = 0, aZero = 0;
        for (double[] s : sos) { if (s[2] == 0) bZero++; if (s[5] == 0) aZero++; }
        int pad = 3 * (2 * sos.length + 1 - Math.min(bZero, aZero));
        validateSignal(x, pad + 1);
        double[] ext = new double[x.length + 2 * pad];
        for (int i = 0; i < pad; i++) {
            ext[i] = 2 * x[0] - x[pad-i];
            ext[pad+x.length+i] = 2*x[x.length-1] - x[x.length-2-i];
        }
        System.arraycopy(x, 0, ext, pad, x.length);
        double[] y = pass(ext, sos);
        reverse(y);
        y = pass(y, sos);
        reverse(y);
        double[] out = new double[x.length];
        System.arraycopy(y, pad, out, 0, out.length);
        validateSignal(out, 1);
        return out;
    }

    private static double[] pass(double[] x, double[][] sos) {
        double[] y = x.clone();
        double initial = x[0];
        for (double[] s : sos) {
            double denominator = 1 + s[4] + s[5];
            if (denominator == 0) throw new IllegalArgumentException("No finite DC steady state");
            double gain = (s[0]+s[1]+s[2]) / denominator;
            double z0 = (gain-s[0]) * initial;
            double z1 = (s[2]-s[5]*gain) * initial;
            initial *= gain;
            for (int i = 0; i < y.length; i++) {
                double input = y[i];
                double output = s[0]*input + z0;
                z0 = s[1]*input - s[4]*output + z1;
                z1 = s[2]*input - s[5]*output;
                y[i] = output;
            }
        }
        return y;
    }

    private static double[][] normalized(double[][] input) {
        if (input == null || input.length == 0) throw new IllegalArgumentException("SOS is empty");
        double[][] out = new double[input.length][6];
        for (int i = 0; i < input.length; i++) {
            if (input[i] == null || input[i].length != 6 || !Double.isFinite(input[i][3]) || input[i][3] == 0)
                throw new IllegalArgumentException("Each SOS row needs six finite coefficients and nonzero a0");
            for (int j = 0; j < 6; j++) {
                out[i][j] = input[i][j] / input[i][3];
                if (!Double.isFinite(out[i][j])) throw new IllegalArgumentException("Non-finite coefficient");
            }
        }
        return out;
    }

    public static void validateSignal(double[] x, int minLength) {
        if (x == null || x.length < minLength) throw new IllegalArgumentException("Signal too short; need " + minLength);
        for (double v : x) if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite signal");
    }

    private static void reverse(double[] a) {
        for (int i = 0; i < a.length/2; i++) { double v=a[i]; a[i]=a[a.length-1-i]; a[a.length-1-i]=v; }
    }
}
