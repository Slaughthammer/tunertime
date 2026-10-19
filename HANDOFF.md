# Handoff: TunerTime Organ

Status as of 19 Oct 2026, release **v18.2-organ**. Everything is on `main`
(GitHub Vollesocke/tunertime); `organ` is the older integration branch that
became v18.0.

## Onno session update (19 Oct 2026)

Implemented in the working tree and installed on Onno's Fairphone 4 local app
(`org.galexander.tunertime.local`, version 18.2-organ):

- Reworked the Preset tab into the Temperierung workbench: temperament name,
  note/deviation table, temporary wolf-fifth selection for Pythagorean and
  meantone presets, and the navigation bar along the bottom. The wolf choice
  lasts until a different temperament is selected. The first four built-in
  temperaments retain their special positions; the rest of the selection list
  is alphabetized using the active locale.
- Added the remaining tunings from Orgelbau Rohlf's list, including the
  Neidhardt village (1732), small-town (1732), large-town (1724), and modified
  temperaments. Existing equivalent entries were not duplicated.
- Removed the optional “Temper note mode” setting. A selected temperament now
  always affects note mode.
- Mixture mode now follows detected notes like tone mode, displays the detected
  pitch, has octave-step buttons, and rejects detections more than 20 cents
  from the nearest expected mixture note. The focus still controls the tuning
  offset reference rather than note recognition.
- Updated organ note labels, length-mode controls, strobe partial-selection
  long-press shortcut, and detector debug recordings/replay hints.

The offline `assembleDev` build succeeded and the APK updated the existing
`.local` package without clearing its data. In this environment the repository
`keystore.properties` symlink points to a missing `/home/greg` file and the
checked-in build type uses the `.dev` suffix. To make the local update, the
source was copied to `/tmp/tunertime-localbuild`, the temporary copy's dev
suffix was changed to `.local`, and it was signed with
`/home/onno/.android/debug.keystore` (SHA-256
`a99683ada5e58b0ff9a1047ed37cf416632407e798eed4ae48be62685581d20c`). The
installed local APK was checked to use that same certificate before updating.
The repository build configuration itself was not changed for this workaround.

Verification this session: `git diff --check` passed and `assembleDev`
completed. No automated test suite was run. Keep the `.dev`, `.local`, and
Play Store packages distinct; the installed Play Store app remains untouched.
The changes are committed on `main` as `Add organ temperament workbench and
tuning updates` and pushed to Onno's fork at
https://github.com/Slaughthammer/tunertime. The upstream repository is
`Vollesocke/tunertime`; the active GitHub account does not have write access
there, so send a pull request from the fork when upstream integration is
wanted.

## Where things stand

- **Releases:** [v18.0-organ](https://github.com/Vollesocke/tunertime/releases/tag/v18.0-organ)
  [v18.1-organ](https://github.com/Vollesocke/tunertime/releases/tag/v18.1-organ)
  and [v18.2-organ](https://github.com/Vollesocke/tunertime/releases/tag/v18.2-organ)
  (Onno's answers), each with an APK attached. The APK is the `dev` build type, app ID
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
  tuning the detector; each recording now has a timeline of what the app did.

## Next steps

1. **Onno tests in a church** with v18.2. Fetch his recordings
   (`adb pull …/files/recordings`), replay them with `test_pitch -t`, and tune
   the detector and thresholds on them (proposal 2 below).
2. **Tidy up the menus** after his tests. There is much more going on than in
   the original app. Onno sees the features as deliberate, long-considered
   ideas, not feature creep, so this is about arrangement, not removal.
3. **Mutations in follow mode:** with continuity, a Quinte 2 2/3' played
   chromatically without its row tapped needs a hand correction on every key
   (simulator: 0/16 right without help, the old rule 15/16). With the row
   tapped it is 16/16. Check with Onno whether tapping the row is acceptable
   practice, or whether continuity should also accept a twelfth.

## Onno's answers (in v18.2)

The questions sent to the organ tuner (`C:\Users\Volker\tuner\TunerTime-Organ-Fragen.html`)
are answered and implemented:

| # | Topic | Decision |
|---|---|---|
| 1 | Length mode and temperature | No change. The tuner allows for the temperature by hand via the A frequency (~0.7 Hz/K); the T± setting only scales the speed of sound. |
| 2 | Octave correction in follow mode | Current note only. After a hand correction, continuity: detections are taken in the octave nearest the note shown and move it only within a fourth (±500 c). Ends on auto off/on or 10 s of silence. `sim_organ -f old/new` compares. Mutations: focus their row. |
| 3 | Cent readout top right in organ mode | Tapped row: its measured offset. No row: "target −05.9c", the set target's offset from equal temperament. |
| 4 | Debug recordings | Same triggers; the .txt now has a timeline of estimates, strobe drift and UI events (`EventLog.java`), on the WAV's time base; `test_pitch -t` replays on the same base. |
| 5 | Wording for stopped pipes | Unchanged. |
| 6 | Mixture step by step | Ton± focuses the reference row last measured again. |

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
3. ~~Octave shift plus a selected row can double the shift~~: gone with
   question 2 (continuity folds, it does not shift).
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
9. **Rohrgedackt option** in length mode: not recommended. It is computed as
   stopped (λ/4); the direction is right, but the mm can be off by some tens
   of percent. Better: an optional calibration (cut 1 mm, measure the cents).

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
- **Realistic simulator:** `tools/sim_organ.c` (46 scenarios: reverb, wind,
  speech, phone mic, mixtures, chromatic runs). It mirrors the Java constants
  DET_AGREE, follow continuity (`-f new`), mixture timing and length logic;
  keep it in sync when changing them.
- **Gradle and the native library:** Gradle once packed a stale
  `libstrobe-jni.so` after ndk-build (same file size). After native changes
  delete `app/build/intermediates/{merged_jni_libs,merged_native_libs,stripped_native_libs}`
  or check the APK (`unzip -l`, grep a new symbol).
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
