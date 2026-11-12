# Leaderboard scopes in Friends

Robolectric native graphics at 320 × 640 dp, with light and dark themes.
All 22 screenshots from the new scopes class and existing Friends class
were reviewed. These 14 new captures cover every added string/state.
They use synthetic accounts.

| State | Light | Dark |
| --- | --- | --- |
| Global: stranger summary and unlisted own rank | [Image](global.png) | [Image](dark_global.png) |
| Global: own row below the top 100 | [Image](global_own_row.png) | [Image](dark_global_own_row.png) |
| Global at 200% text: wrapping scopes | [Image](global_200percent.png) | [Image](dark_global_200percent.png) |
| Global at 200% text: rank stays on one line | [Image](global_200percent_own_row.png) | [Image](dark_global_200percent_own_row.png) |
| Selected group | [Image](group.png) | [Image](dark_group.png) |
| No group memberships | [Image](no_groups.png) | [Image](dark_no_groups.png) |
| Group access refused | [Image](unavailable_group.png) | [Image](dark_unavailable_group.png) |

The existing horizontal period selector scrolls for later periods; scope
choices wrap so Friends, Group and Global remain visible at large text.
The unknown/missing daily fields in stranger rows do not render as zero
reviews or an invitation to refresh private details.

Reproduce from the repository root:

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestLeaderboardScopesScreenshotTest' \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestFriendsScreenshotTest' \
  -Pscreenshot '-Ptheme=light,dark' '-Proborazzi.test.record=true' \
  -PtestMaxParallelForks=1
```

Physical TalkBack, touch exploration and Google Accessibility Scanner
validation remain in #142. These captures do not replace device checks.
