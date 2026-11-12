package com.ecg.filter.benchmark;

import com.ecg.filter.adaptive.AdaptiveZeroPhaseFilter;
import com.ecg.filter.adaptive.FilterConfig;
import com.ecg.filter.butterworth.ZeroPhaseButterworthFilter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 滤波 Benchmark 命令行工具。
 *
 * <p>读取单列 CSV（signal），调用滤波，输出滤波结果 CSV。</p>
 *
 * <p>用法（在项目根目录）：</p>
 * <pre>
 *   mvn exec:java -pl ecg-filter-core \
 *     -Dexec.mainClass="com.ecg.filter.benchmark.BenchmarkRunner" \
 *     -Dexec.classpathScope=test \
 *     -Dexec.args="--input in.csv --config CONFIG_DEFAULT --output out.csv"
 *
 *   # 自适应模式
 *   mvn exec:java ... -Dexec.args="--input in.csv --adaptive --output out.csv"
 * </pre>
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String input = null, output = null, configName = null;
        boolean adaptive = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input": input = args[++i]; break;
                case "--output": output = args[++i]; break;
                case "--config": configName = args[++i]; break;
                case "--adaptive": adaptive = true; break;
            }
        }

        if (input == null || output == null) {
            System.err.println("用法: --input <csv> --output <csv> [--config NAME|--adaptive]");
            System.exit(1);
        }

        double[] signal = readCsv(input);

        double[] filtered;
        if (adaptive) {
            AdaptiveZeroPhaseFilter f = new AdaptiveZeroPhaseFilter();
            f.setWindowSeconds(10.0);
            filtered = f.filter(signal, 250);
        } else {
            FilterConfig config = FilterConfig.valueOf(
                    configName == null ? "CONFIG_DEFAULT" : configName);
            ZeroPhaseButterworthFilter f = new ZeroPhaseButterworthFilter();
            filtered = f.filter(signal, config);
        }

        writeCsv(output, filtered);
        System.out.printf("完成: %s -> %s (%d 样本)%n", input, output, filtered.length);
    }

    static double[] readCsv(String path) throws Exception {
        List<Double> values = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine(); // 表头
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                values.add(Double.parseDouble(line));
            }
        }
        double[] arr = new double[values.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = values.get(i);
        return arr;
    }

    static void writeCsv(String path, double[] signal) throws Exception {
        try (PrintWriter pw = new PrintWriter(path, "UTF-8")) {
            pw.println("signal");
            for (double v : signal) {
                pw.println(Double.toString(v));
            }
        }
    }
}
