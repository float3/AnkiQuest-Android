# Activity and leaderboard within Friends

Part of #139 and #142. The owner approved replacing the separate feed and
leaderboard screens with expandable sections in Friends. Existing links and
notifications open the appropriate section and retain their account and period.

Sections load independently, even if the Friends overview fails. Expansion,
feed filters, Inbox/Sent selection and leaderboard period survive refresh and
rotation. Account changes reset filters and reject late responses. At 200%
text, standings stack their identity, study status and XP to avoid splitting
words into single letters.

Validation on Robolectric API 33: 225 tests passed across 16 focused classes;
eight existing upstream recreation cases remained skipped. This includes the
old feed/leaderboard behavior suites, account-switch and rotation regressions,
legacy redirects, navigation, notification routing, Me sections and Mute Undo.
The initial section regressions failed before implementation. Both large-text
leaderboard captures failed the word-wrapping assertion before the layout fix.

Twenty light/dark screenshots at 320 dp and 100%/200% text passed and were
reviewed: the twelve below and eight existing Mute Undo captures. The generic
activity screenshot provider delegates legacy redirect entries to these
destination tests. Physical-device Accessibility Scanner/TalkBack checks remain
in #142; Robolectric captures do not establish those results.

| State | Light | Dark |
| --- | --- | --- |
| Collapsed sections | [View](friends_collapsed.png) | [View](dark_friends_collapsed.png) |
| Leaderboard | [View](friends_leaderboard.png) | [View](dark_friends_leaderboard.png) |
| Leaderboard, 200% text | [View](friends_leaderboard_200percent.png) | [View](dark_friends_leaderboard_200percent.png) |
| Offline | [View](friends_offline.png) | [View](dark_friends_offline.png) |
| Activity | [View](activity_feed_320dp.png) | [View](dark_activity_feed_320dp.png) |
| Activity, 200% text | [View](activity_feed_320dp_200percent.png) | [View](dark_activity_feed_320dp_200percent.png) |

Reproduce the screenshots with:

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestFriendsScreenshotTest' \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestFeedScreenshotTest' \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestMuteScreenshotTest' \
  -PtestMaxParallelForks=1 -Pscreenshot -Ptheme=light,dark \
  -Proborazzi.test.record=true
```
