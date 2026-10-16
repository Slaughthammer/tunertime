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
  a harmonic-sum search over the spectral peaks, gated on level and
  confidence. Note mode follows after 3 agreeing estimates, holds on silence
  and keeps the cent offset set by hand. The "auto" button cycles
  off → follow → lock → follow; a long press turns it off. Tested in the field
  with a ukulele only; piano, organ pipes (mixtures) and low bass strings are
  the likely octave-error cases.
- **Bench test for the detector** (`tools/test_pitch.c`): synthetic tones from
  A0 to C8 (harmonic, missing fundamental, piano inharmonicity, sine, noisy).
  It is plain C, so build it with any compiler, together with `jni/pitch.c`.
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
- Write non-ASCII characters in XML as entities (e.g. `&#177;`).
- Do not `git add app/src/main` wholesale, because it would commit the built
  `.so` files.

## UI layout tests

`app/src/androidTest` holds instrumented tests (`UiLayoutTest`) that open
every screen, tab, menu and dialog in portrait and landscape, at font scale
1.0 and 1.3, in normal and organ mode, and check each view hierarchy
generically (`UiCheck`): every button, text and the strobe surface must be
inside the window, not cut by its parent, not under the status/navigation bar
or the camera cutout, not overlapping another one, with text that fits;
clickables need 48dp and a label. The Accessibility Test Framework runs
on top (its warnings are only reported). They need Android 11 or later and run
on the `dev` build, on an emulator or a phone:

```sh
./gradlew connectedDevAndroidTest
# one test, one font scale, a name for the output folder:
./gradlew connectedDevAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.galexander.tunertime.UiLayoutTest#main_organ \
  -Pandroid.testInstrumentationRunnerArguments.fontScales=1.3 \
  -Pandroid.testInstrumentationRunnerArguments.device=fairphone4
adb pull /data/local/tmp/tt-ui-tests     # screenshots and report.txt
```

With several devices attached, pick one with `ANDROID_SERIAL`. The tests
reset the dev app's settings and presets, and rotate the phone and change
its font scale while they run (restored at the end).

A test fails only on problems not listed in
`app/src/androidTest/assets/known_ui_issues.txt`, after going through all its
states; the failure message and `report.txt` list them as
`screen|orientation|fontscale|CHECK|element`. Remove a line there once the
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
