# Changelog

本文件记录 ecg-filter-core 从原项目剥离及重构的变更。

## [1.0.0] - 2026-08

### Added（新增）

- **`butterworth/ZeroPhaseButterworthFilter`**：零相移 Butterworth 滤波核心类，从原项目 `ButterworthFilter` + `ButterworthLowPass` 提取封装
- **`adaptive/FilterConfig`**：4 套预定义滤波配置（枚举），含由 `scipy.signal.butter` 精确生成的硬编码系数
- **`adaptive/AdaptiveFilterSelector`**：基于 SQI 的自适应配置选择器，含滞后机制（连续 3 帧才切换）
- **`adaptive/AdaptiveZeroPhaseFilter`**：自适应滤波门面类
- **`sqi/BaselineSQI` / `MuscleSQI` / `KurtosisSQI` / `SQIComputer`**：信号质量指数计算
- **`utils/GaussianElimination`**：自实现高斯消元（求解 `lfilter_zi` 的线性方程组）
- **`utils/Goertzel`**：自实现 Goertzel 频带能量算法
- **`model/SQIResult`**：SQI 结果数据模型

### Changed（变更）

- **`ButterworthFilter` → `ZeroPhaseButterworthFilter`**：核心滤波逻辑从业务模块剥离，包名从 `com.ruoyi.health.aiweidevice.r103.filter` 改为 `com.ecg.filter.butterworth`
- **模块改名**：原 `4g-aiwei-device-module` 改名为 `backend-app`
- **零依赖**：`ecg-filter-core` 无任何运行时外部依赖（仅 JUnit test scope）

### Fixed（修复）

- **`lfilterZi` companion 矩阵转置方向错误**：原实现误将 companion 矩阵放在第一行，导致对接近单位圆极点求解发散。已修正为 `I - companion(a).T`（与 `scipy.signal.lfilter_zi` 一致）

### Known Issues（已知问题）

- **低截止高通下的浮点舍入放大**：0.05 Hz 高通滤波器极点接近单位圆，filtfilt 双向结构会放大 Java 与 SciPy 的浮点舍入差异。实测 48 条 MIT-BIH 记录的 RMSE 均值 1.00×10⁻³ μV、最大 6.22×10⁻³ μV，最小 Pearson r = 0.999999999987。这是数值稳定性特性，非算法错误，工程上等价可安全替代。详见 README「与 SciPy 的精度」一节。
- **`lfilterZi` 运行时精度**：对接近奇异矩阵，自实现高斯消元（非 LAPACK 迭代精化）精度约 1e-4。不影响实际滤波（filtfilt 使用硬编码的精确 ZI 值）。
