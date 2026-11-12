# Private Global controls

Captured with `AnkiquestGlobalHidesScreenshotTest` at 320 dp in light and
dark themes. The large-text cases use 200% font scaling. Tests check
complete names, full action bounds, accessible player labels and disabled
save controls. Existing leaderboard-scope captures remain part of the run.

The first normal-text render clipped Ana's name and split the streak into
narrow fragments. Both theme assertions failed before rows with a Hide
action were stacked vertically.

| State | Light | Dark |
| --- | --- | --- |
| Hide | [Normal](hide.png) | [Normal](dark_hide.png) |
| Hide, 200% text | [Large](hide_200percent.png) | [Large](dark_hide_200percent.png) |
| Hidden players | [Normal](hidden_players.png) | [Normal](dark_hidden_players.png) |
| Hidden players, 200% text | [Large](hidden_players_200percent.png) | [Large](dark_hidden_players_200percent.png) |
| Empty | [Empty](hidden_empty.png) | [Empty](dark_hidden_empty.png) |
| Failed request | [Failure](hidden_failed.png) | [Failure](dark_hidden_failed.png) |
| Saving | [Saving](hidden_saving.png) | [Saving](dark_hidden_saving.png) |

These renders and automated checks do not replace physical-device
TalkBack and Accessibility Scanner verification.
