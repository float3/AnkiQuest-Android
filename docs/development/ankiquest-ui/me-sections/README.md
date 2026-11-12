# Records and history on Me

Records and Study history expand on the player's own Me page. Each section loads
when opened, keeps its filters through refresh and rotation, and clears account
data when the account changes. Old activity links open the matching Me section.
The sections remain available when the profile request fails.

History also keeps its last partial row of statistics visible and uses one column
at large font scales so captions have room for whole words.

## Captures

Generated with `AnkiquestMeScreenshotTest` at 320dp width in light and dark themes.
The `200percent` captures use font scale 2.0. Calendar captures scroll to the
existing heatmap; Records and History captures follow their legacy entry links.

| View | Light | Dark |
| --- | --- | --- |
| Me | [normal](me.png) / [200%](me_200percent.png) | [normal](dark_me.png) / [200%](dark_me_200percent.png) |
| Records | [normal](me_records.png) / [200%](me_records_200percent.png) | [normal](dark_me_records.png) / [200%](dark_me_records_200percent.png) |
| History | [normal](me_history.png) / [200%](me_history_200percent.png) | [normal](dark_me_history.png) / [200%](dark_me_history_200percent.png) |
| Calendar | [normal](me_history_calendar.png) / [200%](me_history_calendar_200percent.png) | [normal](dark_me_history_calendar.png) / [200%](dark_me_history_calendar_200percent.png) |
| Offline | [light](me_offline.png) | [dark](dark_me_offline.png) |

## Validation

- 48 behavior tests pass across Me sections, Profile, Records, History and Home.
- 18 screenshot cases pass. Regression tests were observed failing for separate
  activity navigation, filters surviving an account switch, the missing final
  tile row, and the large-text caption wrapping.
- ktlint 1.8 and `git diff --check` pass.
- Screenshot accessibility scanning ran in LogOnly mode. Its findings remain
  follow-up work under #142: 210 heatmap virtual-day touch-target findings,
  four heatmap speakable-text findings, four text-contrast warnings at scroll
  edges, and three mascot image-contrast warnings. These are not an accessibility
  pass. Physical Google Accessibility Scanner and TalkBack checks are pending.

Reproduce the captures with:

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests com.ichi2.anki.ankiquest.AnkiquestMeScreenshotTest \
  -PtestMaxParallelForks=1 -Pscreenshot -Ptheme=light,dark \
  -Proborazzi.test.record=true -PaccessibilityScan
```
