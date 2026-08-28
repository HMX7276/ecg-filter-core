# ecg-filter-core

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.22136345.svg)](https://doi.org/10.5281/zenodo.22136345)

零外部依赖的 Java 心电（ECG）零相移 Butterworth 滤波库，包含信号质量指数（SQI）计算与自适应滤波配置选择。

A zero-dependency Java library for zero-phase Butterworth filtering of ECG signals, including signal quality index (SQI) computation and adaptive filter selection.

## 特性 / Features

- **零相移 Butterworth 滤波**：完整实现 `scipy.signal.filtfilt` 的 Java 复现（正向滤波 → 反转 → 反向滤波 → 去填充）
- **零外部依赖**：所有数学运算（高斯消元、Goertzel 频带能量）均自实现，无 Apache Commons Math / ND4J / JTransforms 等矩阵库
- **信号质量指数（SQI）**：基线漂移指数（bSQI）、高频噪声指数（mSQI）、峰度指数（kSQI）
- **自适应滤波**：基于 SQI 自动选择最合适的滤波配置，内置滞后机制防闪烁
- **4 套预定义配置**：覆盖静息、基线漂移、肌电/工频噪声、综合噪声四类场景

> **mSQI 指标说明**：mSQI 对 **>35Hz 频段能量**敏感，可同时响应**肌电噪声（EMG）**和**工频干扰（powerline interference）**；其阈值以 50Hz 工频为标定基准。

## 与 SciPy 的精度 / Numerical Equivalence with SciPy

Java 实现与 `scipy.signal.filtfilt` 的算法流程完全一致：
- 正向-反向零相位滤波结构
- Gustafsson 稳态初始条件（`lfilter_zi`）
- 奇数延拓填充（pad = 3 × max(len(b), len(a))）

由于 0.05 Hz 高通滤波器的极点接近单位圆（z≈1），`filtfilt` 的双向递推会放大双精度浮点（IEEE 754 double）的舍入差异。两个数据库的实测结果（生产配置，10 秒记录，250 Hz）：

| 数据集 | 记录数 | 相对 SciPy 的 RMSE（均值 / 最大） | 最小 Pearson r |
|--------|-------|--------------------------------|---------------|
| MIT-BIH Arrhythmia | 48 | 1.00×10⁻³ μV / 6.22×10⁻³ μV | 0.999999999987 |
| PTB-XL | 500 | 4.04×10⁻⁴ μV（均值） | — |

> **结论**：差异处于滤波器条件数决定的数值界限之内，Java 实现与 SciPy 在机器精度上等价，可安全替代。

## 预定义滤波配置 / Predefined Filter Configs

| 配置 | 高通截止 | 低通截止 | 阶数 | 适用场景 |
|------|---------|---------|------|---------|
| `CONFIG_DEFAULT` | 0.05 Hz | 35 Hz | 4 | 静息、信号质量好 |
| `CONFIG_BASELINE_HEAVY` | 0.10 Hz | 35 Hz | 4 | 基线漂移严重 |
| `CONFIG_MUSCLE_HEAVY` | 0.05 Hz | 30 Hz | 4 | 肌电噪声/工频干扰大（mSQI 响应 >35Hz 能量） |
| `CONFIG_CONSERVATIVE` | 0.10 Hz | 30 Hz | 2 | 综合噪声大（防数值不稳定） |

所有系数由 `scipy.signal.butter` 精确生成后硬编码，**默认采样率 250 Hz**。若需支持其他采样率（如 MIT-BIH 的 360 Hz），需用 SciPy 重新生成对应系数。

## 使用示例 / Usage

### Maven 依赖

```xml
<dependency>
    <groupId>com.ecg</groupId>
    <artifactId>ecg-filter-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 固定配置滤波

```java
import com.ecg.filter.adaptive.FilterConfig;
import com.ecg.filter.butterworth.ZeroPhaseButterworthFilter;

ZeroPhaseButterworthFilter filter = new ZeroPhaseButterworthFilter();
double[] signal = ...; // ECG 信号，250 Hz
double[] filtered = filter.filter(signal, FilterConfig.CONFIG_DEFAULT);
```

### 自适应滤波

```java
import com.ecg.filter.adaptive.AdaptiveZeroPhaseFilter;

AdaptiveZeroPhaseFilter adaptive = new AdaptiveZeroPhaseFilter();
double[] filtered = adaptive.filter(signal, 250); // 250 = 采样率
```

### 信号质量指数（SQI）

```java
import com.ecg.filter.model.SQIResult;
import com.ecg.filter.sqi.SQIComputer;

SQIComputer computer = new SQIComputer();
SQIResult sqi = computer.compute(signal, 250);
// sqi.bSQI: 基线漂移指数（0-0.5Hz 能量占比）
// sqi.mSQI: 高频噪声指数（>35Hz 能量占比，可响应肌电噪声和工频干扰，阈值以 50Hz 工频为标定基准）
// sqi.kSQI: 峰度指数（正常 ECG >3，噪声大时降低）
```

## 运行测试 / Run Tests

```bash
git clone https://github.com/HMX7276/ecg-filter-core.git
cd ecg-filter-core
mvn test
```

测试包括：
- `SciPyEquivalenceTest`：与 `scipy.signal.filtfilt` 的精度对比
- `SQITest`：合成噪声信号的 SQI 识别
- `AdaptiveSelectorTest`：自适应配置切换（含滞后机制）
- `DiagnosticFidelityTest`：诊断特征保真度（框架预留）

## 项目结构 / Structure

```
com.ecg.filter
├── butterworth/          # 零相移 Butterworth 滤波核心
│   └── ZeroPhaseButterworthFilter
├── adaptive/             # 自适应滤波
│   ├── FilterConfig        # 4 套预定义配置（枚举）
│   ├── AdaptiveFilterSelector
│   └── AdaptiveZeroPhaseFilter
├── sqi/                  # 信号质量指数
│   ├── BaselineSQI
│   ├── MuscleSQI
│   ├── KurtosisSQI
│   └── SQIComputer
├── model/                # 数据模型
│   └── SQIResult
└── utils/                # 数学工具
    ├── GaussianElimination # 高斯消元（自实现）
    └── Goertzel            # 频带能量（自实现）
```

## 重要约定 / Important Convention

**QRS 宽度变化**（若后续接入诊断特征保真度）在此处明确定义为
"滤波前后 QRS 起止点检测位置的绝对偏移量之和"，而非"QRS 持续时间的变化"。

## 数据来源 / Data

本仓库不包含任何 ECG 原始数据。论文实验使用的四个数据库均公开于 PhysioNet，请自行下载：
[MIT-BIH Arrhythmia Database](https://physionet.org/content/mitdb/)、
[MIT-BIH Noise Stress Test Database](https://physionet.org/content/nstdb/)、
[PTB-XL](https://physionet.org/content/ptb-xl/)、
[Fantasia](https://physionet.org/content/fantasia/)。
`src/test/resources/scipy_reference.csv` 为 SciPy 参考输出的单元测试数据（合成信号），非 PhysioNet 记录。

## 引用 / Citation

如果本代码对你的研究有帮助，请引用：

> He, L. *Zero-Phase Butterworth Filtering for Resource-Constrained ECG Monitoring: A Validated Pure-Java Implementation with Signal-Quality-Guided Adaptive Configuration.* Biomedical Signal Processing and Control (under review), 2026.

代码存档（永久 DOI）：<https://doi.org/10.5281/zenodo.22136345>

## License

[MIT](LICENSE)
