# Android exports of the alpha cleanup

These five 336px WebPs come from the approved local cleanup of Aki's comeback,
freeze-used, hot, blazing and legend PNG masters in float3/ankiquest#88.
The master edit removes only pixels with alpha 1 through 4; pixels above alpha
4 and all dimensions remain unchanged. It preserves the original contours,
colors and glow.

[Actual Android before/after images](review.png) are shown at 128px and 64px on
light and dark backgrounds. Each output was regenerated twice with the existing
exporter's Lanczos resize, quality 88, full-quality alpha and method 6, and
matched byte-for-byte. [Export hashes](exports.json) pin the five masters and
corresponding WebPs. No resource names or behavior changed.

The full Ubuntu unit workflow and normal PR checks remain required before merge.
Physical notification, widget and launcher checks have not been run.
