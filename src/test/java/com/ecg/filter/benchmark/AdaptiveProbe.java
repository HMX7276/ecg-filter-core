package com.ecg.filter.benchmark;

import com.ecg.filter.adaptive.AdaptiveFilterSelector;
import com.ecg.filter.adaptive.FilterConfig;
import com.ecg.filter.model.SQIResult;
import com.ecg.filter.sqi.SQIComputer;

import java.io.File;
import java.io.FilenameFilter;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * 自适应探针：对目录下所有 CSV 计算 SQI + 选择的配置，输出汇总 CSV。
 *
 * <p>用于自适应配置切换准确率测试。输出 CSV 列：record, bSQI, mSQI, kSQI, config</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java -cp "target/classes;target/test-classes" \
 *     com.ecg.filter.benchmark.AdaptiveProbe --input-dir <dir> --output <csv>
 * </pre>
 */
public class AdaptiveProbe {

    public static void main(String[] args) throws Exception {
        String inputDir = null, output = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input-dir": inputDir = args[++i]; break;
                case "--output": output = args[++i]; break;
            }
        }
        if (inputDir == null || output == null) {
            System.err.println("用法: --input-dir <dir> --output <csv>");
            System.exit(1);
        }

        File inDir = new File(inputDir);
        File[] csvFiles = inDir.listFiles(new FilenameFilter() {
            @Override public boolean accept(File dir, String name) { return name.endsWith(".csv"); }
        });
        if (csvFiles == null) { System.exit(1); }
        Arrays.sort(csvFiles);

        SQIComputer sqiComputer = new SQIComputer();
        AdaptiveFilterSelector selector = new AdaptiveFilterSelector();

        try (PrintWriter pw = new PrintWriter(output, "UTF-8")) {
            pw.println("record,bSQI,mSQI,kSQI,config");
            for (File csv : csvFiles) {
                double[] signal = BenchmarkRunner.readCsv(csv.getAbsolutePath());
                SQIResult sqi = sqiComputer.compute(signal, 250);
                FilterConfig config = selector.decide(sqi.bSQI, sqi.mSQI);
                pw.printf("%s,%.6f,%.6f,%.2f,%s%n",
                        csv.getName(), sqi.bSQI, sqi.mSQI, sqi.kSQI, config.name());
            }
        }
        System.out.printf("SQI 探针完成: %d 个 CSV → %s%n", csvFiles.length, output);
    }
}
