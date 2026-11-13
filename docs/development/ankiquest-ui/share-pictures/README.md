# Local share pictures

Part of [#248](https://github.com/float3/AnkiQuest-Android/issues/248).

Every card share supplies its public display content to `AnkiquestShare.picture`.
Optional win metadata and the canonical PNG each get one second. When unavailable,
the same helper draws a local picture and gives Android the PNG, caption and link.
An unsaved conquest-label suggestion is never part of the exported content.
Rendering or file failures are logged and preserve the text-only share.

The chooser's **Download picture** action saves the same file to the gallery.
Android 10+ uses MediaStore pending writes; older Android requests storage
permission. The action retains the account and removes an incomplete gallery row
when copying or publishing fails. Each share has its own temporary file; files
older than a day are removed from AnkiQuest's share cache.

## Native captures

`AnkiquestPictureScreenshotTest` covers a conquest, its 200% font variant, a
streak milestone, a daily goal and a weekly podium in light and dark themes.
All ten captures were visually reviewed for clipping and readable labels.

| Card | Light | Dark |
| --- | --- | --- |
| Conquest | [Image](conquest.png) | [Image](dark_conquest.png) |
| Conquest, 200% font | [Image](conquest_large_font.png) | [Image](dark_conquest_large_font.png) |
| Milestone | [Image](milestone.png) | [Image](dark_milestone.png) |
| Daily goal | [Image](goal.png) | [Image](dark_goal.png) |
| Weekly podium | [Image](weekly_podium.png) | [Image](dark_weekly_podium.png) |

```sh
./gradlew :AnkiDroid:testPlayDebugUnitTest \
  --tests 'com.ichi2.anki.ankiquest.AnkiquestPictureScreenshotTest' \
  -Pscreenshot -Ptheme=light,dark -Proborazzi.test.record=true
```

Screenshot mode selects screenshot tests only. Run ordinary regression tests
separately, without `-Pscreenshot`.

## Device checks still required

These native captures and Robolectric tests do not verify receiving apps.

| Target | PNG opens | Caption and link retained |
| --- | --- | --- |
| WhatsApp | Not tested on a device | Not tested on a device |
| Signal | Not tested on a device | Not tested on a device |
| Telegram | Not tested on a device | Not tested on a device |
| Google Messages | Not tested on a device | Not tested on a device |

Also verify Download picture from a real chooser on Android 7 and Android 10+,
including denying permission, retrying and switching accounts. Issue #248 stays
open until these checks are recorded.
