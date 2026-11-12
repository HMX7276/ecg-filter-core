# Physical-device ECG replay

This app executes the actual Java `OverlapAddStream` on three public-data streams. It requires no network, contacts, sensors or personal-data permissions. It is a research replay harness, not a medical monitoring application.

The measured signed APK is distributed in this directory as `ecg-replay-research.apk`. Its SHA256 is `0ec769118f17bdc88a07770e1d05cc09d76e68a023a5423f3d30f585b036a3c1`. From a clean checkout or release archive, run `python scripts/summarize_android.py` to verify the supplied raw sessions without rebuilding the APK. A rebuilt APK has a different signature and is a new experimental artifact; it must not be substituted for the measured APK when auditing the supplied sessions.

Build on the recorded Windows environment (Python 3.11, JDK 21, Android SDK platform 35 and build-tools 36.1.0):

```text
python scripts/build_android_replay.py
```

Run `revision_experiments.py` first to regenerate the three 600 s inputs under `results/revision/work`. The builder embeds the inputs, freshly generated desktop-Java reference outputs and source/file hashes. It creates a non-debuggable D8 release APK under `target/android-replay`. The signing key is a local research-test key, not a production publishing credential. The app package is `org.ecgrevision.replay`.

The input streams use MIT-BIH records 100 and 207 and Fantasia f1o01, with the revision's known additive-noise schedule. Sources: [MIT-BIH](https://physionet.org/content/mitdb/1.0.0/), [Fantasia](https://physionet.org/content/fantasia/1.0.0/) and [NSTDB](https://physionet.org/content/nstdb/1.0.0/). These dataset pages identify ODC-By 1.0. Preserve [DATA_SOURCES.txt](DATA_SOURCES.txt) and the applicable dataset terms when redistributing the embedded waveform excerpts; they are not covered by the Java library's MIT license.

## ADB execution

```text
python scripts/run_android_replay.py
python scripts/summarize_android.py
```

The runner installs only this test package, restarts it for three sessions and collects only its output. An authorized physical Xiaomi phone is required. It rejects emulators and multiple ambiguous devices; use `--serial` to select a device when needed.

## Manual execution and file transfer

Copy `ecg-replay-research.apk` into the phone's Download directory and install it. Open **ECG Replay Research**, tap **开始回放测试**, wait for completion, and tap **退出测试程序**. That exit button terminates the app process. Repeat from the launcher to obtain three independent process sessions; the three input records tested inside one session are not three process sessions.

The app records process IDs and timestamps and rotates input order between sessions. Results are stored under the app's `Android/data/org.ecgrevision.replay/files/session_*` directory and exported to `Download/ECGReplay/session_*.zip` on Android 10+. Windows MTP may require reconnecting USB, selecting file-transfer mode and unlocking the phone to refresh the directory listing.

Import copied exports or copied raw session folders:

```text
python scripts/summarize_android.py --exports results/android/exports
# Or, after copying session_* folders into mtp_import:
python scripts/summarize_android.py --imports results/android/mtp_import
```

The verifier checks APK/source/input hashes, complete outputs against desktop reference arrays, all per-call rows, and distinct process IDs. If extra sessions exist, the first three chronological complete runs with distinct process IDs are selected without using performance outcomes; all selection reasons and raw runs remain available.

Each input uses five warmup and twenty measured replays. There are 119 complete frames per 600 s replay and 21,420 measured full frames across three inputs and three sessions. Caller buffers, input loading, log writing and memory sampling are outside each timed `accept` call. The terminal `finish` call is recorded separately. Memory samples include the whole app and replay buffers, not the stream's minimum live state. Accelerated replay does not measure sensor, communications or alarm latency.
