# Deck-sharing Undo

Saving returns to Decks or Settings with an Undo snackbar. Undo restores
the previous recipients for that deck, or each deck's own previous
settings after a bulk edit. It is available only after a successful save
and while the same account and foreground screen still own the action.

Native Robolectric captures at 320dp, in light and dark themes. All eight
captures were reviewed. The owner approved replacing the bulk result
dialog with Undo. Physical TalkBack and Accessibility Scanner checks
remain outstanding.

| Entry point | Light | Dark |
| --- | --- | --- |
| Deck menu | [Normal](deck_undo.png) | [Normal](dark_deck_undo.png) |
| Deck menu, 200% text | [Large](deck_undo_200percent.png) | [Large](dark_deck_undo_200percent.png) |
| Bulk settings | [Normal](bulk_undo.png) | [Normal](dark_bulk_undo.png) |
| Bulk settings, 200% text | [Large](bulk_undo_200percent.png) | [Large](dark_bulk_undo_200percent.png) |
