# Aki, the AnkiQuest study companion

Aki is the owner-approved orange-and-cream red panda with an indigo scarf,
flashcard satchel and ringed tail. Original transparent PNG artwork was created
for AnkiQuest with OpenAI's image-generation tool. The source prompts are saved
beside this document. The artwork ships inside the app and works offline.

## App integration

- Adaptive and legacy launcher icons, including the monochrome themed icon.
- Native Today header and translated encouragement based on local cards and
  current server progress; neutral encouragement when daily data has expired.
- Completed daily quests and session summaries.
- Deck-list review heatmap, widgets and the AnkiQuest settings entry.
- Existing local notifications, using a monochrome small icon and a sampled
  face bitmap as the large icon.

The seven pictures are in `AnkiDroid/src/main/res/drawable-nodpi/aki_*.webp`,
downscaled to about three times their largest display size; the full-size PNG
originals live in the ankiquest server repository under `addon/`.
Use welcome for introductions, review for studying, celebrate for confirmed
completion, streak for today's studying, freeze for confirmed streak
protection, winner for completed quests or records, and face for compact
branding. Keep the adaptive icon foreground inside the Android safe area.

The local collection remains the source of truth for remaining cards. Finishing
one deck must not imply every deck is finished. Owning a freeze does not imply
protection. Aki's voice is encouraging, brief and free from guilt or threats.

Illustrations are decorative and excluded from accessibility focus. Messages
are normal English/Spanish text that wraps and respects system text sizing.
Do not replace player pictures or add mascot controls to a study session.

The companion server/web and desktop changes use the same artwork. Branding
does not change review weights, scoring, alert schedules, permissions, accounts
or user settings.
