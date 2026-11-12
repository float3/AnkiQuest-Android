# Streak protection with Undo

Part of #141. Pause and Resume apply immediately on Me. The existing translated
explanation appears beneath the control; the current status and saved freezes
remain on the profile. Undo appears after a successful server response and
restores the previous setting once. Failure retains the previous setting and
offers no inverse. The shared helper discards Undo on expiry, account changes
and leaving the screen.

Three new behavior tests failed against the confirmation dialog. After the
change, all 26 focused Profile, Me-section and Undo tests passed on Robolectric
API 33. Eight light/dark screenshots at 320 dp and 100%/200% text passed and were
reviewed; their assertions require the updated control and Undo to fit on screen.
The captures scroll to the updated control after reloading the profile because
Pause and Resume can wrap differently. No new strings or visibility changes.

| State | Light | Dark | Light, 200% text | Dark, 200% text |
| --- | --- | --- | --- | --- |
| Paused | [View](protection_paused.png) | [View](dark_protection_paused.png) | [View](protection_paused_200percent.png) | [View](dark_protection_paused_200percent.png) |
| Resumed | [View](protection_resumed.png) | [View](dark_protection_resumed.png) | [View](protection_resumed_200percent.png) | [View](dark_protection_resumed_200percent.png) |

Reproduce with:

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestProtectionScreenshotTest' \
  -PtestMaxParallelForks=1 -Pscreenshot -Ptheme=light,dark \
  -Proborazzi.test.record=true
```

Physical-device Accessibility Scanner/TalkBack validation remains in #142.
Deck-share Undo and the remaining #141 picker/form work remain separate tasks.
