# Transparent streak widget

Issue #168 adds a separate **AnkiQuest Streak — Transparent** entry to the launcher widget picker. It uses the existing cached day verdict, streak minimum, daily goal and tap target. The original card entry stays available.

The transparent layouts have shadowed white text and a black-and-white outline around the progress indicators. The small layout reserves space for the goal indicator at its advertised 57 × 57 dp minimum. Android 12+ chooses responsive layouts; earlier Android versions redraw on resize and retain the existing explicit text fitting.

## Captures

`AnkiquestClearStreakScreenshotTest` renders four minimum footprints (57 × 57, 100 × 90, 200 × 220 and 300 × 380 dp), each on a light and a dark wallpaper in both app themes. Each grid shows, in row order: partial minimum, confirmed minimum with a daily goal, protected day, no streak, locally met minimum awaiting upload, and expired/unknown state. The partial example includes a next milestone; the other active examples use three-digit streaks.

The test asserts the number, status and progress fit inside the widget before saving each image. The companion tests cover both provider entries, transparent-only installations, shared content descriptions, account changes, pre-Android-12 resize refresh, and pre-Android-8 text fitting.

These are Robolectric native-rendering captures. Physical launcher resizing, TalkBack and arbitrary photo wallpapers still need device verification.

Regenerate from the Android repository root:

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestClearStreakScreenshotTest' \
  -Pscreenshot -Ptheme=light,dark -Proborazzi.test.record=true \
  -PtestMaxParallelForks=1
```
