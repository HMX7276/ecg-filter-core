# ecg-filter-core

Dependency-free Java forward-backward Butterworth filtering for **delayed ECG processing**.

v1.1.0 返修版：新增 SOS、BWA 会话校准、去均值 mSQI、归一化窗口重叠相加与复现实验。旧直接型接口保留用于对照，旧固定 bSQI 自适应接口已弃用。

Versioned release: [v1.1.0](https://github.com/HMX7276/ecg-filter-core/releases/tag/v1.1.0).

## Scope and timing

- Inputs: 250 Hz, microvolts. Other sampling rates are rejected.
- Odd padding and step-steady initial states match SciPy's padding convention. This is **not Gustafsson's method**.
- Use SOS for new applications. No universal numerical-error floor or clinical-safety claim is made.
- Streaming: 10 s frames / 5 s hop; first output after 10 s acquisition, emitted samples 5-10 s old plus computation/delivery.
- Calibration: 11 overlapping frames spanning the first 60 s; three-candidate hysteresis adds two hops (10 s).
- Calibration-complete status does not certify clean input. Initial contamination can cause failure.
- Zero phase does not guarantee unchanged amplitude/ST/R-peak measurements. Overlap-add does not reproduce whole-record filtering.

## Fixed SOS API

```java
import com.ecg.filter.butterworth.SosZeroPhaseFilter;
import com.ecg.filter.adaptive.FilterConfig;
double[] output = new SosZeroPhaseFilter().filter(inputUv, FilterConfig.CONFIG_DEFAULT);
```

High-pass and low-pass are filtered separately forward and backward. Cutoffs below are single-pass -3 dB frequencies (approximately -6 dB after two passes); order is per stage, per pass.

| Configuration | HP Hz | LP Hz | Order |
|---|---:|---:|---:|
| CONFIG_DEFAULT | 0.05 | 35 | 4 |
| CONFIG_BASELINE_HEAVY | 0.10 | 35 | 4 |
| CONFIG_MUSCLE_HEAVY | 0.05 | 30 | 4 |
| CONFIG_CONSERVATIVE | 0.10 | 30 | 2 |

The last name is a historical mixed-noise label, not a certified safety mode.

## Delayed stream API

```java
import com.ecg.filter.streaming.OverlapAddStream;
OverlapAddStream stream = new OverlapAddStream();
double[] finalizedSamples = stream.accept(chunkUv); // repeat per incoming chunk
// Call only at the actual end of a recording:
double[] remainingSamples = stream.finish();
```

Use a fresh stream for each record. Output can be empty until a full frame arrives. Total output length after finish equals input length. End padding repeats the endpoint and holds the last configuration, rather than inventing measured future data. Nonfinite input is rejected. Bounded frame state excludes caller buffers and JVM overhead; no minimum-heap claim is made.

The deprecated `AdaptiveZeroPhaseFilter` retains old fixed bSQI thresholds and independent windows. It does not implement this revised pipeline. Short legacy blocks fail explicitly instead of silently changing padding semantics.

## Reproduce

Python 3.11 and JDK 17+ (set JAVA_HOME) are required. Runtime Java code has no dependencies and is compiled for Java 8 compatibility. Tests executed on JDK 21.0.9.

```text
python -m venv .venv
# Activate the environment with your shell, then:
python -m pip install -r scripts/requirements.txt
python scripts/generate_sos.py
python -m pytest scripts/test_revision.py -q
python scripts/download_data.py
python scripts/revision_experiments.py
python scripts/summarize_revision.py
# Regenerate figures alone from saved CSV files:
python scripts/render_figures.py
# Independent uncentered-index ablation:
python scripts/revision_experiments.py --uncentered
```

Data download: all 48 MIT-BIH records; Fantasia f1o01-04 and f1y01-04; NSTDB bw/em/ma; first 600 s. URLs, leads, units and SHA256 values are saved in data/manifest.json. The calibration/assessment split is saved in results/revision/split.json; MIT-BIH 201/202 stay together. Raw data and scratch buffers are excluded from Git.

Outputs include every record/window CSV and 300 dpi PNG plus vector PDF figures, particularly fig1_numerical_equivalence, fig3_stability and fig5_streaming. summarize_revision.py adds participant intervals, Figure 2 and desktop timing. Paths are repository-relative. Original submission figures with unavailable original records/logs cannot be reconstructed from screenshots.

Run `mvn test` when Maven is installed, or compile src/main/java and src/test/java using the JUnit console runner and include src/test/resources. Python tests invoke actual Java and cover SciPy equivalence, orders 2/4/6/8, low cutoffs, configurations, BWA, chunk invariance, terminal lengths and DC invariance. DiagnosticFidelityTest only checks a real filter call's length/finiteness; analytical ST measurements are in the experiment script, not clinical validation.

## Retrospective clinical-record extension

```text
python scripts/download_clinical.py
python scripts/clinical_experiments.py --workers 3
python scripts/summarize_clinical.py
python -m pytest scripts/test_clinical_metrics.py -q
```

The exploratory plan and disclosed technical amendments are in `docs/retrospective_protocol.json`. The extension uses all 48 MIT-BIH records (47 participants), attempts all 40 Fantasia subjects (34 evaluated; six excluded for native missing ECG samples), and all 90 European ST-T records (79 participants). It invokes the actual Java processing branches, uses the same XQRS detector in each branch, and retains unfavorable outcomes. Results and downloadable-source hashes are under `results/clinical`.

European ST-T analysis accepts 246 of 368 parsed expert peak labels across 68 participants; all 122 landmark/reference failures are inventoried. This is annotation-assisted peak measurement, not autonomous episode detection. End-to-end HRV compares uncorrected detected intervals with reference NN intervals; its errors include ectopy and detection effects. The separate matched-NN timing audit conditions on annotated beat classes and successful matching. Neither is a safety certificate. Participant-level inference groups repeated-subject records and reports sparse nonzero pairs, exact sign enumeration where appropriate, and Holm adjustment across four exploratory contrasts.

The complete verification set comprises 36 cross-language Python checks, nine clinical metric/statistics checks and 16 JUnit tests. Test success establishes the stated software behavior, not clinical efficacy.

## Physical Android replay

Build, run, transfer and verify instructions are in [android-replay/README.md](android-replay/README.md). The app uses stored public ECG excerpts and exports per-call timing, resource observations, device/build metadata and complete output arrays. One app session contains three input records; three independent app processes are required by the protocol. The supplied signed APK and raw result files allow verification without rebuilding or access to a signing key. Device evidence and its scope are documented in [docs/android_benchmark_protocol.md](docs/android_benchmark_protocol.md).

## Evidence and release status

Repeated-SNR inference aggregates by participant, combining 201/202. The fixed shared noise excerpts and exploratory DC correction limit independent generalization claims. Negative SNR results are retained. Desktop measurements are not Android measurements; see docs/android_benchmark_protocol.md.

Upstream baseline: bffd1ff0d79a5a0e4736a6f29e380c147298748c.
All-versions DOI: https://doi.org/10.5281/zenodo.22136345.
Version-specific v1.1.0 DOI: https://doi.org/10.5281/zenodo.24155161.
The revised implementation and record-level evidence are distributed as GitHub release v1.1.0. Cite that version for the revised experiments. The existing GitHub-Zenodo integration archived v1.1.0 as DOI 10.5281/zenodo.24155161. Every archived file was verified against the released commit `83ad4c5565c017c9b30eb01c441478014627bf25`. Use the version-specific DOI for reproducible citation.

The signed measured APK is included at `android-replay/ecg-replay-research.apk`; `python scripts/summarize_android.py` verifies its source and asset hashes and all four raw sessions, selecting the first three independent processes. This works directly from the release archive without an Android SDK or private signing key. Full public source waveforms are downloaded separately; the APK contains three attributed public-data excerpts under the terms in `android-replay/DATA_SOURCES.txt`.

## License

[MIT](LICENSE). Source datasets retain their own published terms.
