# Handoff: TunerTime Organ

Status as of 16 Oct 2026, release **v18.1-organ**. Everything is on `main`
(GitHub Vollesocke/tunertime); `organ` is the older integration branch that
became v18.0.

## Where things stand

- **Releases:** [v18.0-organ](https://github.com/Vollesocke/tunertime/releases/tag/v18.0-organ)
  and [v18.1-organ](https://github.com/Vollesocke/tunertime/releases/tag/v18.1-organ),
  each with an APK attached. The APK is the `dev` build type, app ID
  `org.galexander.tunertime.dev`, labelled "TunerTime Organ". It installs next
  to Greg's Play Store app.
- **Features:** see README "What this fork adds" and the release notes. In
  short: organ mode (footage rows, tap to select a partial, mixture mode,
  length mode), German note names and interface, organ-aware pitch detection
  with debug recordings, microphone selection, new name and icon.
- **Tested:**
  - Synthetic tones: `tools/test_pitch`, 537/537.
  - Realistic organ simulator: `tools/sim_organ`, 262/264 keys detected,
    0 wrong notes.
  - Automated UI tests on emulators like the Fairphone 4 and Galaxy S25: clean
    except one known issue.
  - The app starts and runs on a real Galaxy S25 (Android 16).
- **Not tested:** real organ pipes in a real church. That is the next
  important step. Follow-mode corrections save WAV recordings (dev build) for
  tuning the detector.

## Open questions (sent to Onno)

Until they are answered the app keeps its current behaviour. The questionnaire
is `C:\Users\Volker\tuner\TunerTime-Organ-Fragen.html`; its answer line looks
like `1b 18 °C, 2c, 3b, 4ab, 5c, 6b`.

| # | Topic | Now | Options |
|---|---|---|---|
| 1 | Length mode and temperature | a¹ = 440 Hz at the current air temperature. At 12 °C every pipe sounds ~17–18 c lower than at 18 °C, so pipes cut in winter are too short in summer. | a) keep · b) fixed reference temperature, length converted to it |
| 2 | Octave correction in follow mode | Applies to the whole stop until auto is held or 10 s of silence. | a) whole stop · b) this pipe only · c) ask once |
| 3 | Cent readout top right in organ mode | Measured offset of the tapped partial; empty when nothing is tapped. | a) keep · b) always the 8', tapped row when tapped |
| 4 | Debug recordings | Only on note/octave corrections in follow mode. | also on: a) lock · b) auto off · c) tapping a row |
| 5 | Wording for stopped pipes in length mode | "too long: cut off / too short: lengthen", also for stopped pipes. | a) keep · b) "cap lower / higher" · c) "shorten / lengthen" · d) own |
| 6 | Mixture step by step | After Ton± the reference row must be tapped again. | a) keep · b) re-select the last measured reference row |

Settled by the user: 4/5' (not 2/5') and 3 1/5' in the footage list, no other
extra footages. Mixture ranks are muted by hand (abstecken). 2 s mixture
averaging stays, to be tested in practice (now 3 s below 8'). Length mode
shows only the mm to cut or add.

## Proposals not done yet, ranked

1. **Key released before a 3 s bass measurement ends:** the mixture
   measurement keeps collecting the release and reverb tail. The simulator
   showed large errors. Stop or discard the measurement on release. The
   release notes tell users to hold the key.
2. **Real recordings:** tune `MIN_CONF` (jni/pitch.c), the beat threshold
   `BEAT_SWING` 0.7 (mixture.java) and `STABLE_CENTS` (pipelength.java) on
   real organ WAVs (`tools/test_pitch file.wav`).
3. **Octave shift plus a selected row can double the shift** (TunerTime
   follow logic plus pitch.c focus). Decide together with question 2.
4. **Mixture: warn when the reference is more than about 1 semitone from the
   target,** where the strobe drift wraps.
5. **Stop profiles** (larger): a preset per stop with its rows, open/stopped
   and expected octave, e.g. "Gedackt 8'", "Mixtur IV 1 1/3'".
6. **Detector above 9 kHz:** raising `MAX_PARTIAL` broke top pipes in the
   simulator. Leave it unless real data says otherwise.
7. **NDK r28** instead of r21. Needed for Play Store publishing; the 16 KB
   alignment flag is already in `jni/Android.mk`.
8. **Patches for Greg** (optional): the fixes to his original code, i.e. the
   preset note editor crash, Android 15 edge-to-edge and 16 KB pages, could go
   to him as small separate patches.

Measurements and plots: `tools/measure/FINDINGS.md`. The same report is
`C:\Users\Volker\tuner\TunerTime-Organ-Messbericht.html`. FINDINGS.md quotes
numbers from before the simulation fixes and DET_AGREE 4.

## Building on this PC

Details in README "Building"; what is specific here:

- **JDK:** `JAVA_HOME` defaults to JDK 11, which fails. Use
  `export JAVA_HOME="C:\\Program Files\\Java\\jdk-22"`.
- **NDK 21** (`%LOCALAPPDATA%\Android\Sdk\ndk\21.1.6352462`), called through
  the 8.3 short path:
  ```sh
  NDK=$(cygpath -d "$LOCALAPPDATA/Android/Sdk/ndk/21.1.6352462"); P=$(cygpath -d "$PWD")
  cmd //c "$NDK\\ndk-build.cmd NDK_PROJECT_PATH=$P NDK_LIBS_OUT=$P\\app\\src\\main\\lib -j8"
  ./gradlew assembleDev --offline
  ```
- **Network:** the network here drops TLS connections, especially from Java.
  Gradle's cache is complete, so always build with `--offline`. Fetch
  downloads with curl in a retry loop.
- **Signing key:** `keystore.properties` points to
  `C:\Users\Volker\tuner\keys\debug.keystore`, the other PC's debug key
  (SHA-256 `21:42:89:17:…:49:DC`). Builds from either PC update the same
  installed app. Keep `keys\` out of the repo.
- **Local files out of git:** `local.properties`, `keystore.properties` and
  `app/src/main/lib` are marked `skip-worktree`. Build output, `.claude/` and
  `obj/` are in `.git/info/exclude`.
- **Line endings:** most files are CRLF in the working tree
  (`core.autocrlf=true`). `sed -i` in Git Bash silently strips the CRs; use an
  editor, Python with `newline=''`, or `unix2dos`.
- **Icon:** `python tools/make_icon.py` redraws all launcher icon sizes.

## Phone, emulators, tests

- **Galaxy S25 over Wi-Fi:** `adb connect 192.168.178.104:5555`, using the
  SDK's `platform-tools\adb.exe` (not on PATH). After a phone restart, enable
  it again over USB with `adb tcpip 5555`. Never run tests or uninstall on the
  phone: the dev app there holds the user's presets.
- **Emulators** (hardware acceleration works): `tt_fairphone4` (API 33,
  420 dpi) and `tt_galaxy_s25` (API 35, 450 dpi), both with cutout and
  3-button navigation.
- **UI tests:** README "UI layout tests". Always set
  `ANDROID_SERIAL=emulator-…`, or Gradle uses every device, including the
  phone. The suite runs in its own `uitest` build type. Known issues are in
  `app/src/androidTest/assets/known_ui_issues.txt`.
- **Detector bench:** `tools/test_pitch.c` (537 synthetic cases; also replays
  WAVs).
- **Realistic simulator:** `tools/sim_organ.c` (38 scenarios: reverb, wind,
  speech, phone mic, mixtures). It mirrors the Java constants DET_AGREE,
  mixture timing and length logic; keep it in sync when changing them.
- **Measurements:** `CC=/c/Strawberry/c/bin/gcc sh tools/measure/run.sh`
  (about 35 min).
- **Debug recordings from the phone:**
  `adb pull /sdcard/Android/data/org.galexander.tunertime.dev/files/recordings`.

## Housekeeping

- 14 agent worktrees are still under `.claude/worktrees/`, with their
  branches (`worktree-agent-…` and the feature branches). Everything in them
  is merged into `main`. Remove them with `git worktree remove <path>` and
  `git branch -d <branch>` when no longer needed.
- Play Store publishing is planned for later, with Greg's approval. It needs:
  - its own app ID and signing key
  - NDK r28
  - a privacy policy (microphone) and the data safety form
  - closed testing with about 12 testers for 14 days
