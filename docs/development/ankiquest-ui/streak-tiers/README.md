# Streak widget tiers

Each image shows 1, 7, 30, 100, 365 and 1,000 days, left to right then down.
The light and dark variants cover the minimum sizes: 57 × 57, 100 × 90,
200 × 220 and 300 × 380 dp. All eight images were visually reviewed.

The flame grows at each tier. Large and huge widgets show the next milestone
until 365 days. Missing, stale and zero streaks clear both optional details.
The number fits the available width; the medium label stays on one line.

Recorded with `AnkiquestStreakScreenshotTest`, `-Pscreenshot`,
`-Ptheme=light,dark` and `-Proborazzi.test.record=true`. Its host resolves layout
direction as a launcher does, so relative compound drawables are included.

Native layout tests run on API 27 and 33. Android 7 RemoteViews and reapply
behavior run on API 24; physical launcher and Accessibility Scanner checks
have not been run. Robolectric has no native font measurements on API 24.
