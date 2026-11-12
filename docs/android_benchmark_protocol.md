# Completed physical Android replay

The original Android 16 / 12.7 ms summary lacks retained raw instrumentation. New measurements of the revised implementation are supplied separately under `results/android`; none of the old timing values were reused.

Actual device metadata: Xiaomi 17 Pro, model 25098PN5AC, SoC SM8850, Android 17, API 37, arm64-v8a. The full fingerprint, security patch and runtime metadata are in each raw `results.json`. The non-debuggable APK uses compile/target SDK 35, min SDK 26 and D8 release conversion with build-tools 36.1.0. APK SHA256: `0ec769118f17bdc88a07770e1d05cc09d76e68a023a5423f3d30f585b036a3c1`.

Three independently restarted processes replay three 600 s streams, each with five warmups and twenty measured replays. Input order rotates. The verifier selects the first three chronological complete runs with distinct process IDs, without selecting timing outcomes; raw runs and selection reasons are retained. There are 180 measured replays, 21600 accept calls and 21420 full-frame calls. These nested calls are not independent device experiments.

Pooled full-frame mean 0.999488 ms, median 1.071510 ms, nearest-rank p95 1.211146 ms, maximum 3.822083 ms. Per-session/input distributions and all calls are available. Nine saved phone outputs contain 150000 samples each; maximum host-recomputed RMSE 1.313572e-14 uV and absolute error 4.547474e-13 uV.

Timing includes actual `OverlapAddStream.accept` computation and allocation; it excludes asset loading, caller chunk creation, stream construction, copying, logging and resource sampling. Terminal handling is separate. Java used heap observations and PSS snapshots include the whole app and replay buffers; their maxima are 203.965 MiB and 103.355 MiB. They are not minimum streaming memory requirements or guaranteed peak live sets.

Battery temperatures span 35.8-35.8 C. Recorded plugged codes: [2]; power-save states: [False]; thermal status codes: [0]. The screen stays on; default thread priority and ordinary background workload are used. CPU clocks, background processes and ART compilation are not experimentally fixed, and no forced GC is used. Runtime/GC counters and native/PSS snapshots are retained for inspection.

The author operated the physical phone. Four exported ZIP files were collected by temporary local-network upload; the first session also exactly matched the earlier USB-transferred files. The first three chronological distinct processes were analyzed and the fourth remains available for audit. The upload service was closed after collection. Build/manual/ADB instructions are in `android-replay/README.md`. The accelerated replay measures neither sensor acquisition nor communications, battery life or alarm latency. The 10 s startup input buffer, 5 s output hop and two additional hysteresis hops remain algorithmic delays. This single-model experiment does not establish device-wide Android performance or clinical efficacy.
