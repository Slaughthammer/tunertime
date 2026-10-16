# TunerTime (fork)

A fork of [TunerTime](http://galexander.org/), the Android strobe tuner by
Greg Alexander. Upstream lives at `http://galexander.org/git/tunertime.git`.
The `upstream` branch here mirrors its `master` as of this fork, and `main`
holds the changes below. The user manual is in `doc/index.html`, and the
author's development log is `NOTES`.

TunerTime is free software under the GNU GPL v2 (see `COPYING`), and so is
this fork.

## What this fork adds

- **Auto note detection** (`jni/pitch.c`). A separate detector runs next to
  the strobes: 2:1 decimation, an 8192-point Hann FFT every 2048 samples, and
  a harmonic-sum search over the spectral peaks (candidates: each peak / 1..6
  and the spacing of neighbouring peaks, so a missing or weak fundamental is
  found, and stopped pipes with odd partials only do not come out an octave
  high), gated on level and on the share of peak energy that lies on the
  winner's harmonics. Down to C0 (32' C). Note mode follows after 3 agreeing
  estimates, holds on silence and keeps the cent offset set by hand. The
  "auto" button cycles off → follow → lock → follow; a long press turns it
  off. In organ mode a focused partial (`parms.focus_ratio`) makes the note
  that partial's peak / ratio. Tested in the field with a ukulele only.
- **Corrections while following** (`TunerTime.on_pitch`): moving the note by
  hand while following remembers the interval from the detected note (e.g.
  an octave for a 4' stop) and applies it to later detections, until auto is
  turned off, 10 s of silence, or the correction is undone.
- **Debug recordings** (`DebugRecorder.java`, `jni/record.c`; Settings →
  "Debug recordings", on in the dev build): each hand correction while
  following saves 5 s before and 2 s after as a 16-bit mono WAV plus a .txt
  with the detected and the corrected note. Get them with
  `adb pull /sdcard/Android/data/org.galexander.tunertime.dev/files/recordings`.
- **Bench test for the detector** (`tools/test_pitch.c`): synthetic tones from
  A0 to C8 (harmonic, missing fundamental, piano inharmonicity, sine, noisy)
  and organ pipes (Gedackt, Quintadena, Principal, reeds, 16'/32' through a
  phone mic, mixtures, focused partials). `test_pitch file.wav [focus_ratio
  [expect_hz]]` replays a recording and prints the estimates over time. It
  is plain C, so build it with any compiler, together with `jni/pitch.c`.
- **Realistic organ bench** (`tools/sim_organ.c`): simulated pipes (speech
  transients, chiff, reed onset, wind, tremulant, celeste, mixtures, 32'
  to 1') in a reverberant church through a phone (high-pass mic, AGC,
  clipping), with blower hum, speech and footsteps; runs them through
  `jni/pitch.c` and `jni/strobe.c` the way the app does and reports
  detection latency and errors, length-mode stability and the mixture
  measurement's error. `-w dir` writes the scenarios as WAV files for
  `test_pitch`. Build: `gcc -O2 -Ijni -Itools/hostjni -o sim_organ
  tools/sim_organ.c jni/pitch.c jni/strobe.c -lm`.
- **Selectable harmonic rows**: Settings → "Harmonics shown", any of 1..20,
  up to 8 strobe rows.
- **Presets in tenths of a cent** (`preset.UNITS_PER_CENT`, 48000 = A4), with
  ±1c / ±0.1c buttons and a ± key in the note editor. Existing whole-cent
  presets and settings are migrated.
- **A 12-note preset can temper note mode** (Settings toggle, on by default).
  The title shows the active temperament and offset.
- **Preset management** (`PresetIO.java`): long-press for
  edit/rename/duplicate/move/export/delete, sort, import and export all.
  Plain-text, hand-editable format, described in the header of every
  exported file.
- **Historical temperaments** (`temperaments.java`): Pythagorean, ¼ and ⅙
  comma meantone, Werckmeister III, Kirnberger III, Vallotti, Young II and
  Kellner, normalised to A = 0 so the A-frequency setting still means the A
  you hear.
- **Neutral dark UI** (`res/values/colors.xml`, theme `Tuner`), with the
  native drawing in `jni/fft.c` and `jni/strobe.c` using the same palette.
  Both the strobe (new default colour scheme) and the FFT spectrogram use
  Crameri's batlow colormap; slate, red/green and the others are still in
  Settings.
- **Android 15+ edge-to-edge fixes**: no screen draws under the status or
  navigation bar, and the strobe tab bar no longer covers the last row.
- **`dev` build type**: `org.galexander.tunertime.dev`, labelled
  "TunerTime dev", which installs next to the Play Store app with its own
  settings and presets.

Open ideas from users, in `NOTES`: octave advance when stepping past the end
of a preset, and CSV import/export.

## Building

You need the Android SDK and NDK (tested with NDK 28.2) and JDK 17.

1. Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.
2. Point `keystore.properties` at a keystore. The Android debug keystore
   works (`~/.android/debug.keystore`, passwords `android`, alias
   `androiddebugkey`).
3. Build the native library, then the app:

   ```sh
   ndk-build NDK_PROJECT_PATH=. NDK_LIBS_OUT=app/src/main/lib -j8
   ./gradlew assembleDev       # or assembleRelease
   ```

On Windows:

- Git checks the upstream symlinks out as text files. Replace
  `app/src/main/lib` with a real directory, and edit `local.properties` and
  `keystore.properties` in place. `git update-index --skip-worktree` keeps
  those local edits out of commits.
- The NDK does not accept paths with spaces, so call it through the 8.3 short
  path (`cygpath -d`).

Traps when editing:

- Files mix LF and CRLF line endings, and some contain Latin-1 bytes. Keep
  each file's existing line endings and encoding.
- Write non-ASCII characters in XML as entities (e.g. `&#177;`), also
  in the German `res/values-de/strings.xml` (`&#228;` for a-umlaut).
- User-visible text belongs in `res/values/strings.xml` with its German
  in `res/values-de/strings.xml` (same keys, same order); none in
  layouts or Java.  The native code's English row labels are translated
  in `Strobe.shown_labels()`.
- Do not `git add app/src/main` wholesale, because it would commit the built
  `.so` files.

## UI layout tests

`app/src/androidTest` holds instrumented tests (`UiLayoutTest`) that open
every screen, tab, menu and dialog in English and German, at font scale 1.0
and 1.3, in portrait and landscape, in normal and organ mode, and check each view hierarchy
generically (`UiCheck`): every button, text and the strobe surface must be
inside the window, not cut by its parent, not under the status/navigation bar
or the camera cutout, not overlapping another one, with text that fits;
clickables need 48dp and a label. The Accessibility Test Framework runs
on top (its warnings are only reported). They need Android 11 or later (13
for the language switch) and run on their own `uitest` build
(`org.galexander.tunertime.uitest`, next to the Play Store and dev apps), on an
emulator or a phone:

```sh
./gradlew connectedUitestAndroidTest
# one test, one language and font scale, a name for the output folder:
./gradlew connectedUitestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.galexander.tunertime.UiLayoutTest#main_organ \
  -Pandroid.testInstrumentationRunnerArguments.languages=de \
  -Pandroid.testInstrumentationRunnerArguments.fontScales=1.3 \
  -Pandroid.testInstrumentationRunnerArguments.device=fairphone4
adb pull /data/local/tmp/tt-ui-tests     # screenshots and report.txt
```

With several devices attached, pick one with `ANDROID_SERIAL` (otherwise
Gradle runs on all of them). The tests rotate the phone and change its font
scale while they run (restored at the end). Gradle uninstalls the uitest app
afterwards; to keep it, install both APKs (`assembleUitest
assembleUitestAndroidTest`) and run `adb shell am instrument -w -e device NAME
org.galexander.tunertime.uitest.test/androidx.test.runner.AndroidJUnitRunner`.

A test fails only on problems not listed in
`app/src/androidTest/assets/known_ui_issues.txt`, after going through all its
states; the failure message and `report.txt` list them as
`screen|language|orientation|fontscale|CHECK|element`. Remove a line there once the
problem is fixed (`-e strict true` fails on the known ones too).

Emulators matching the target phones (1080x2340; the Fairphone 4 at 420dpi
with Android 13, the Galaxy S25 at 450dpi with Android 15), with a
hole-punch cutout:

```sh
sdkmanager emulator "system-images;android-33;default;x86_64" \
  "system-images;android-35;default;x86_64"
avdmanager create avd -n tt_fairphone4 -d pixel_4 \
  -k "system-images;android-33;default;x86_64"
# in ~/.android/avd/tt_fairphone4.avd/config.ini: hw.lcd.height=2340,
# hw.lcd.density=420 (450 for the S25 one), hw.ramSize=2048M
emulator -avd tt_fairphone4 -no-window &
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole
```
