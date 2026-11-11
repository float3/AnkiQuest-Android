# Three tabs and Me

Reviewed Robolectric renders recorded on 2026-11-11. Each case has a light
and dark variant.

- `today_320dp`: Today with the avatar and three bottom tabs.
- `today_goal_320dp`: Today with a daily goal.
- `today_320dp_200percent`: Today at 200% text.
- `me` / `me_200percent`: Me at normal and 200% text.
- `me_offline`: account settings remain reachable when the profile fails.

The avatar tests check its button role, single label and 48 dp touch target.
These are simulated screens; device Accessibility Scanner verification
remains pending.

Record with `:AnkiDroid:testPlayDebugUnitTest`, filtering to
`com.ichi2.anki.ankiquest.AnkiquestHomeScreenshotTest.today*` and
`com.ichi2.anki.ankiquest.AnkiquestMeScreenshotTest`, and properties
`-Pscreenshot -Ptheme=light,dark -Proborazzi.test.record=true`.
