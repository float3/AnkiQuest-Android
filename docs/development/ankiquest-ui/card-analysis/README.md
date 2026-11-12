# Card Analysis widget sizing

These native Robolectric captures cover light and dark themes, 84 × 56 dp minimum,
175 × 102 dp compact, 175 × 220 dp, 262 × 220 dp, and 349 × 220 dp widgets.
The minimum, compact and 262 × 220 dp cases also use 200% font scaling.
Empty-collection and missing-deck states are included. All 20 captures were reviewed.

| State | Light | Dark |
| --- | --- | --- |
| Minimum | [normal](minimum_size.png), [200%](minimum_large_font.png) | [normal](dark_minimum_size.png), [200%](dark_minimum_large_font.png) |
| 2 × 1 | [normal](size_2x1.png), [200%](size_2x1_large_font.png) | [normal](dark_size_2x1.png), [200%](dark_size_2x1_large_font.png) |
| 2 × 2 | [normal](size_2x2.png) | [normal](dark_size_2x2.png) |
| 3 × 2 | [normal](size_3x2.png), [200%](size_3x2_large_font.png) | [normal](dark_size_3x2.png), [200%](dark_size_3x2_large_font.png) |
| 4 × 2 | [normal](size_4x2.png) | [normal](dark_size_4x2.png) |
| Missing deck | [light](missing_deck.png) | [dark](dark_missing_deck.png) |
| Empty collection | [light](empty_collection.png) | [dark](dark_empty_collection.png) |

Reproduce:

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests 'com.ichi2.anki.widget.cardanalysis.CardAnalysisWidgetScreenshotTest' \
  -Pscreenshot -Ptheme=light,dark -Proborazzi.test.record=true
```

`CardAnalysisWidgetLayoutTest` separately checks text bounds, explicit ellipsis,
and count contrast against the actual rendered background. Its Android 7 cases
exercise the pre-O update and resize paths with native autosizing disabled,
using newer Robolectric native font metrics. These are not Android 7 device runs.
Physical launcher sizing, TalkBack, Accessibility Scanner and arbitrary dynamic
wallpaper palettes remain unverified.
