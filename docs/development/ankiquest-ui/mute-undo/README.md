# Immediate mute with Undo

Part of #141. The owner approved replacing the existing mute confirmation
with immediate mute and Undo on both Friends and Profile.

The shared module-only helper offers Undo after the server confirms success.
It invalidates the action after the screen stops, the account changes, another
action replaces it, or the snackbar expires. Undo can run only once. Failed
requests keep each screen's existing error feedback and offer no inverse.
Manual Unmute dismisses the pending Undo. The snackbar stays above Friends'
bottom tabs.

Validation on Robolectric API 33: 35 focused tests passed (19 Friends, 10
Profile, six helper tests). The immediate-mute regressions failed before the
implementation. A separate screenshot assertion reproduced the tab overlap
before anchoring the snackbar above the navigation.

Eight light/dark captures at 320 dp and 100%/200% text passed and were reviewed.
Their assertions check that Undo is visible, its text has at least 4.5:1
contrast against the theme's inverse surface, and it does not overlap the tabs.
Physical-device Accessibility Scanner/TalkBack checks remain in #142.

| Screen | Light | Dark | Light, 200% text | Dark, 200% text |
| --- | --- | --- | --- | --- |
| Friends | [View](friends_mute.png) | [View](dark_friends_mute.png) | [View](friends_mute_200percent.png) | [View](dark_friends_mute_200percent.png) |
| Profile | [View](profile_mute.png) | [View](dark_profile_mute.png) | [View](profile_mute_200percent.png) | [View](dark_profile_mute_200percent.png) |

The other #141 flows remain separate work: streak-protection and deck-sharing
Undo, picker/recipient consolidation, and stable forms before inline composers.
