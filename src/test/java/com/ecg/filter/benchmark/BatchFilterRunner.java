package com.ecg.filter.benchmark;

import com.ecg.filter.adaptive.AdaptiveZeroPhaseFilter;
import com.ecg.filter.adaptive.FilterConfig;
import com.ecg.filter.butterworth.ZeroPhaseButterworthFilter;

import java.io.File;
import java.io.FilenameFilter;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * 批量滤波工具：处理目录下所有 CSV，输出滤波结果。
 *
 * <p>用法：</p>
 * <pre>
 *   mvn exec:java -pl ecg-filter-core \
 *     -Dexec.mainClass="com.ecg.filter.benchmark.BatchFilterRunner" \
 *     -Dexec.classpathScope=test \
 *     -Dexec.args="--input-dir ../data/processed/mitbih_250hz --config CONFIG_DEFAULT --output-dir ../results/mitbih_filtered"
 * </pre>
 */
public class BatchFilterRunner {

    public static void main(String[] args) throws Exception {
        String inputDir = null, outputDir = null, configName = "CONFIG_DEFAULT";
        boolean adaptive = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input-dir": inputDir = args[++i]; break;
                case "--output-dir": outputDir = args[++i]; break;
                case "--config": configName = args[++i]; break;
                case "--adaptive": adaptive = true; break;
            }
        }

        if (inputDir == null || outputDir == null) {
            System.err.println("用法: --input-dir <dir> --output-dir <dir> [--config NAME|--adaptive]");
            System.exit(1);
        }

        File inDir = new File(inputDir);
        File[] csvFiles = inDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".csv");
            }
        });
        if (csvFiles == null) {
            System.err.println("输入目录无 CSV: " + inputDir);
            System.exit(1);
        }
        Arrays.sort(csvFiles);

        new File(outputDir).mkdirs();

        ZeroPhaseButterworthFilter fixedFilter = new ZeroPhaseButterworthFilter();
        AdaptiveZeroPhaseFilter adaptiveFilter = new AdaptiveZeroPhaseFilter();
        adaptiveFilter.setWindowSeconds(10.0);
        FilterConfig config = FilterConfig.valueOf(configName);

        int count = 0;
        for (File csv : csvFiles) {
            double[] signal = BenchmarkRunner.readCsv(csv.getAbsolutePath());
            double[] filtered;
            if (adaptive) {
                filtered = adaptiveFilter.filter(signal, 250);
            } else {
                filtered = fixedFilter.filter(signal, config);
            }
            File out = new File(outputDir, csv.getName());
            BenchmarkRunner.writeCsv(out.getAbsolutePath(), filtered);
            count++;
        }
        System.out.printf("批量滤波完成: %d 个 CSV → %s%n", count, outputDir);
    }
}
