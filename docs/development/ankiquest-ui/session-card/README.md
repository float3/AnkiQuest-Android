# Combined session card

Reviewed Robolectric renders from `AnkiquestSessionScreenshotTest`, recorded
on 2026-11-11. Each case has a light and dark variant.

- `session_card_recap` / `session_card_moment`: normal text.
- `session_card_*_200percent`: 200% text, including the scrolled recap opt-out.
- `session_card_small_*`: 320 × 568 dp, including the scrolled opt-out.

The tests check full action visibility, opt-out reachability and full-width
pages after an accessibility scroll. These are simulated screens; device
Accessibility Scanner verification remains pending.

Record with `:AnkiDroid:testPlayDebugUnitTest`, filtering to
`com.ichi2.anki.ankiquest.AnkiquestSessionScreenshotTest`, and properties
`-Pscreenshot -Ptheme=light,dark -Proborazzi.test.record=true`.
