#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Compose the README banner, docs/graphics/logos/banner_readme.png (1280x741).

The banner shows AnkiQuest's title, a tagline, Aki, and five phones in a fan with the
app's own screens, left to right: a card being reviewed, the deck list with each deck's
daily status and the review heatmap, Today in front, the week leaderboard and a profile.
The screens come from AnkiquestBannerScreenshots, which renders them with Robolectric from
sample decks and players.

Regenerate (from the repository root):

    # 1. Record the five screens (360x780dp at xhdpi, 720x1560 px) into
    #    AnkiDroid/build/outputs/roborazzi/AnkiquestBannerScreenshots/
    ./gradlew :AnkiDroid:recordRoborazziPlayDebug -Pscreenshot -PreadmeBanner \
        --tests '*AnkiquestBannerScreenshots*'

    # 2. Compose the banner (needs Pillow, e.g. in a scratch venv)
    python -m venv /tmp/banner-venv && /tmp/banner-venv/bin/pip install Pillow
    /tmp/banner-venv/bin/python tools/ankiquest_banner.py

The result is the same on every run: the screens use fixed sample data, a fixed clock for
the server screens, a fixed review card and a heatmap pinned to 28 October 2026.

The test is skipped unless -PreadmeBanner is given, so the screenshot workflows never run it.
.github/workflows/ankiquest_banner.yml runs both steps for every quest-* release tag and opens
a pull request against main when the banner changed.

The reviewer's card is a WebView, which Robolectric draws blank: the test draws the card's
rendered question and answer natively over it, in the default note type's style.

Text is set in Roboto (Apache 2.0), read from the Robolectric native-runtime jar that
step 1 downloads into the Gradle cache; pass --font-dir for a folder holding
Roboto-Bold.ttf and Roboto-Regular.ttf instead. Without either, a common system sans
font is used and the script says so.
"""

from __future__ import annotations

import argparse
import glob
import io
import os
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "AnkiDroid/build/outputs/roborazzi/AnkiquestBannerScreenshots"
AKI = ROOT / "AnkiDroid/src/main/res/drawable-nodpi/aki_celebrate.webp"
OUT = ROOT / "docs/graphics/logos/banner_readme.png"

TITLE = "AnkiQuest"
TAGLINE = "Study together, keep your streak"

WIDTH, HEIGHT = 1280, 741
# Brand colours: res/values/ankiquest_home.xml (aq_home_primary) and the goal bar.
TOP = (43, 102, 217)  # aq_goal_bar_end
BOTTOM = (24, 66, 124)  # a deeper aq_home_primary
GLOW = (255, 162, 0)  # aq_goal_gold_end, a warm light behind the front phone

# Phone geometry (banner pixels), modelled on the upstream AnkiDroid banner.
SCREEN_W = 240
SCREEN_H = round(SCREEN_W * 1560 / 720)  # the recorded 360x780dp screens
STATUS_H = 14
BEZEL_X, BEZEL_TOP, BEZEL_BOTTOM = 12, 40, 36
PHONE_W = SCREEN_W + 2 * BEZEL_X
PHONE_H = BEZEL_TOP + STATUS_H + SCREEN_H + BEZEL_BOTTOM
PHONE_TOP = 124

# Back to front, so nearer phones cover the ones behind them: (screen, centre x).
FAN = [
    ("1_reviewer", 160),
    ("5_profile", 1120),
    ("2_decks", 400),
    ("4_leaderboard", 880),
    ("3_today", 640),
]


def roboto(
    font_dir: str | None, require: bool = False
) -> tuple[ImageFont.FreeTypeFont, ImageFont.FreeTypeFont, ImageFont.FreeTypeFont]:
    """Title, tagline and status-bar fonts."""

    def load(bold: bytes | str, regular: bytes | str):
        def font(source, size):
            # The basic layout engine everywhere: Raqm, where installed, spaces text differently.
            return ImageFont.truetype(
                io.BytesIO(source) if isinstance(source, bytes) else source, size, layout_engine=ImageFont.Layout.BASIC
            )

        return font(bold, 66), font(regular, 32), font(regular, 10)

    if font_dir:
        return load(os.path.join(font_dir, "Roboto-Bold.ttf"), os.path.join(font_dir, "Roboto-Regular.ttf"))
    gradle = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    jars = sorted(glob.glob(str(gradle / "caches/modules-2/files-2.1/org.robolectric/nativeruntime-dist-compat/*/*/*.jar")))
    for jar in reversed(jars):
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            if {"fonts/Roboto-Bold.ttf", "fonts/Roboto-Regular.ttf"} <= names:
                return load(archive.read("fonts/Roboto-Bold.ttf"), archive.read("fonts/Roboto-Regular.ttf"))
    if require:
        sys.exit("Roboto not found in the Gradle cache: record the screens first, or pass --font-dir")
    for bold, regular in [
        ("C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/segoeui.ttf"),
        ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        ("/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf"),
    ]:
        if os.path.exists(bold) and os.path.exists(regular):
            print(f"Roboto not found; using {bold}", file=sys.stderr)
            return load(bold, regular)
    sys.exit("No font found: pass --font-dir with Roboto-Bold.ttf and Roboto-Regular.ttf")


def background() -> Image.Image:
    gradient = Image.linear_gradient("L").resize((WIDTH, HEIGHT))
    canvas = Image.composite(Image.new("RGBA", (WIDTH, HEIGHT), BOTTOM), Image.new("RGBA", (WIDTH, HEIGHT), TOP), gradient)
    glow = Image.new("L", (WIDTH, HEIGHT), 0)
    ImageDraw.Draw(glow).ellipse((340, 250, 940, 900), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    canvas.paste(Image.new("RGBA", (WIDTH, HEIGHT), GLOW), (0, 0), glow)
    return canvas


def phone(screen: Image.Image, status_font: ImageFont.FreeTypeFont) -> Image.Image:
    """A phone body holding [screen], with a status bar tinted like the screen's top bar."""
    body = Image.new("RGBA", (PHONE_W, PHONE_H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(body)
    draw.rounded_rectangle((0, 0, PHONE_W - 1, PHONE_H - 1), radius=34, fill=(28, 30, 33), outline=(70, 74, 80), width=2)
    draw.rounded_rectangle((5, 5, PHONE_W - 6, PHONE_H - 6), radius=30, outline=(48, 51, 56), width=2)
    # Earpiece, camera and the lower speaker.
    draw.rounded_rectangle((PHONE_W // 2 - 34, 18, PHONE_W // 2 + 34, 23), radius=3, fill=(55, 58, 63))
    draw.ellipse((PHONE_W // 2 - 70, 14, PHONE_W // 2 - 58, 26), fill=(40, 44, 52), outline=(70, 74, 80))
    draw.rounded_rectangle((PHONE_W // 2 - 48, PHONE_H - 21, PHONE_W // 2 + 48, PHONE_H - 16), radius=3, fill=(55, 58, 63))

    top = screen.convert("RGB").crop((0, 0, screen.width, 4)).resize((1, 1), Image.Resampling.BOX).getpixel((0, 0))
    status = tuple(round(c * 0.82) for c in top)
    light = sum(top) / 3 > 150
    ink = (40, 44, 52) if light else (255, 255, 255)
    x, y = BEZEL_X, BEZEL_TOP
    draw.rectangle((x, y, x + SCREEN_W - 1, y + STATUS_H - 1), fill=status)
    draw.text((x + 8, y + STATUS_H // 2), "15:11", font=status_font, fill=ink, anchor="lm")
    # Signal, wifi and battery, simplified.
    right = x + SCREEN_W - 8
    draw.rectangle((right - 12, y + 4, right - 1, y + STATUS_H - 4), fill=ink)
    draw.rectangle((right - 1, y + 6, right, y + STATUS_H - 6), fill=ink)
    draw.polygon([(right - 28, y + STATUS_H - 4), (right - 18, y + STATUS_H - 4), (right - 18, y + 3)], fill=ink)

    shot = screen.convert("RGB").resize((SCREEN_W, SCREEN_H), Image.Resampling.LANCZOS)
    body.paste(shot, (x, y + STATUS_H))
    return body


def shadow(size: tuple[int, int]) -> Image.Image:
    mask = Image.new("L", (size[0] + 80, size[1] + 80), 0)
    ImageDraw.Draw(mask).rounded_rectangle((40, 46, size[0] + 40, size[1] + 46), radius=44, fill=110)
    return mask.filter(ImageFilter.GaussianBlur(16))


def compose(shots: Path, font_dir: str | None, require_roboto: bool = False) -> Image.Image:
    title_font, tagline_font, status_font = roboto(font_dir, require_roboto)
    canvas = background()
    for name, centre in FAN:
        path = shots / f"{name}.png"
        if not path.is_file():
            sys.exit(f"Missing {path}: record AnkiquestBannerScreenshots first (see this script's docstring)")
        body = phone(Image.open(path), status_font)
        left = centre - PHONE_W // 2
        canvas.paste((0, 0, 0, 255), (left - 40, PHONE_TOP - 40), shadow(body.size))
        canvas.alpha_composite(body, (left, PHONE_TOP))

    # Aki beside the title and tagline, the three centred together.
    draw = ImageDraw.Draw(canvas)
    aki_size, gap = 116, 10
    text_w = max(draw.textlength(TITLE, font=title_font), draw.textlength(TAGLINE, font=tagline_font))
    text_x = WIDTH / 2 + (aki_size + gap) / 2
    draw.text((text_x, 44), TITLE, font=title_font, fill="white", anchor="mm")
    draw.text((text_x, 96), TAGLINE, font=tagline_font, fill=(255, 240, 224), anchor="mm")
    aki = Image.open(AKI).convert("RGBA").resize((aki_size, aki_size), Image.Resampling.LANCZOS)
    canvas.alpha_composite(aki, (round(text_x - text_w / 2 - gap - aki_size), 4))
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--shots", type=Path, default=SHOTS, help="folder with the recorded screens")
    parser.add_argument("--font-dir", help="folder with Roboto-Bold.ttf and Roboto-Regular.ttf")
    parser.add_argument("--require-roboto", action="store_true", help="fail instead of falling back to a system font")
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()
    banner = compose(args.shots, args.font_dir, args.require_roboto)
    assert banner.size == (WIDTH, HEIGHT)
    banner.save(args.out, optimize=True)
    print(f"Wrote {args.out}")


if __name__ == "__main__":
    main()
