//! The banner's layout: background, five phones in a fan, and Aki and
//! Ankilope beside the title and tagline.
//!
//! Sizes and colours are those of the Pillow script this replaces, so the
//! layout is unchanged; the server's banner (float3/ankiquest, `xtask
//! banner`) uses the same colours, fonts and renderer.

use image::{RgbImage, RgbaImage};

use crate::draw::{self, Anchor, Canvas, Fonts, Weight};

/// Banner size in pixels.
pub(crate) const WIDTH: u32 = 1280;
pub(crate) const HEIGHT: u32 = 741;

const TITLE: &str = "AnkiQuest";
const TAGLINE: &str = "Study together, keep your streak";

/// Text sizes and weights: title, tagline, status bar clock.
const TITLE_FONT: (f32, Weight) = (66.0, Weight::Bold);
const TAGLINE_FONT: (f32, Weight) = (32.0, Weight::Regular);
const STATUS_FONT: (f32, Weight) = (10.0, Weight::Regular);

/// Vertical middles of the title and tagline lines.
const TITLE_MIDDLE: f32 = 44.0;
const TAGLINE_MIDDLE: f32 = 96.0;

/// Brand colours: res/values/ankiquest_home.xml (aq_home_primary) and the
/// goal bar. Gradient top (aq_goal_bar_end), bottom (a deeper
/// aq_home_primary), and a warm light behind the front phone
/// (aq_goal_gold_end).
const TOP: &str = "#2b66d9";
const BOTTOM: &str = "#18427c";
const GLOW: [u8; 3] = [255, 162, 0];
const TAGLINE_INK: &str = "#fff0e0";

/// The glow: an ellipse's centre and radii, opacity (70 of 255) and blur.
const GLOW_CENTRE: (f32, f32) = (640.5, 575.5);
const GLOW_RADII: (f32, f32) = (300.5, 325.5);
const GLOW_OPACITY: f32 = 70.0 / 255.0;
const GLOW_BLUR: f32 = 120.0;

/// The recorded screens: 360x780dp at xhdpi.
const SHOT_W: u32 = 720;
const SHOT_H: u32 = 1560;

/// Phone geometry in banner pixels, modelled on the upstream AnkiDroid
/// banner.
const SCREEN_W: u32 = 240;
const SCREEN_H: u32 = SCREEN_W * SHOT_H / SHOT_W;
const STATUS_H: u32 = 14;
const BEZEL_X: u32 = 12;
const BEZEL_TOP: u32 = 40;
const BEZEL_BOTTOM: u32 = 36;
const PHONE_W: u32 = SCREEN_W + 2 * BEZEL_X;
const PHONE_H: u32 = BEZEL_TOP + STATUS_H + SCREEN_H + BEZEL_BOTTOM;
const PHONE_TOP: i32 = 124;

/// Phone colours: body, outer and inner rim, earpiece and speaker, camera.
const BODY: &str = "#1c1e21";
const RIM: &str = "#464a50";
const INNER_RIM: &str = "#303338";
const GRILLE: &str = "#373a3f";
const LENS: &str = "#282c34";

/// Status bar: clock, dimming of the screen's top colour, and the
/// brightness above which the bar takes dark ink.
const CLOCK: &str = "15:11";
const STATUS_DIM: f32 = 0.82;
const LIGHT: u32 = 150;
const DARK_INK: [u8; 3] = [40, 44, 52];
const LIGHT_INK: [u8; 3] = [255, 255, 255];

/// Rows at the top of a screen whose average colours the status bar.
const TOP_ROWS: u32 = 4;

/// Phone shadow: opacity (110 of 255), blur, drop, corner radius, and the
/// margin the blur may spread into.
const SHADOW_OPACITY: f32 = 110.0 / 255.0;
const SHADOW_BLUR: f32 = 16.0;
const SHADOW_DROP: i32 = 6;
const SHADOW_RADIUS: u32 = 44;
const SHADOW_MARGIN: i32 = 40;

/// Aki and Ankilope: height (the phones start below it), gap to the text,
/// top.
const DUO_H: u32 = 116;
const DUO_GAP: f32 = 12.0;
const DUO_TOP: i32 = 4;

/// Back to front, so nearer phones cover the ones behind them: screen name
/// and centre x. Left to right: a card being reviewed, the deck list,
/// Today in front, the week leaderboard and a profile.
pub(crate) const FAN: [(&str, i32); 5] = [
    ("1_reviewer", 160),
    ("5_profile", 1120),
    ("2_decks", 400),
    ("4_leaderboard", 880),
    ("3_today", 640),
];

/// Draws the banner from the screens, in `FAN` order, and the duo art.
pub(crate) fn compose(
    screens: &[RgbaImage; FAN.len()],
    duo: &RgbaImage,
    fonts: &Fonts,
) -> Result<RgbImage, String> {
    let mut canvas = Canvas::new(WIDTH, HEIGHT, fonts)?;
    canvas.svg(&sky())?;
    canvas.picture(&glow()?, 0, 0)?;

    // Phones, back to front, all with the same shadow.
    let shadow = shadow()?;
    for (screen, (_, centre)) in screens.iter().zip(FAN) {
        let left = centre - (PHONE_W / 2) as i32;
        canvas.picture(&shadow, left - SHADOW_MARGIN, PHONE_TOP - SHADOW_MARGIN)?;
        phone(&mut canvas, fonts, screen, left)?;
    }

    // The duo beside the title and tagline, the three centred together.
    let duo = draw::trim(duo);
    let width = (f64::from(duo.width()) * f64::from(DUO_H) / f64::from(duo.height()))
        .round_ties_even() as u32;
    let duo = draw::shrink(&duo, width, DUO_H);
    let text_w = text_width(fonts, TITLE, TITLE_FONT).max(text_width(fonts, TAGLINE, TAGLINE_FONT));
    let (text_x, duo_x) = heading(duo.width(), text_w);
    canvas.svg(&format!(
        "{}{}",
        line(fonts, TITLE, TITLE_FONT, "white", (text_x, TITLE_MIDDLE)),
        line(
            fonts,
            TAGLINE,
            TAGLINE_FONT,
            TAGLINE_INK,
            (text_x, TAGLINE_MIDDLE)
        ),
    ))?;
    canvas.picture(&duo, duo_x, DUO_TOP)?;

    Ok(canvas.into_rgb())
}

/// The gradient.
fn sky() -> String {
    format!(
        r#"<defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
<stop offset="0" stop-color="{TOP}"/><stop offset="1" stop-color="{BOTTOM}"/>
</linearGradient></defs>
<rect width="{WIDTH}" height="{HEIGHT}" fill="url(#sky)"/>"#
    )
}

/// The warm light behind the front phone.
fn glow() -> Result<RgbaImage, String> {
    let ((cx, cy), (rx, ry)) = (GLOW_CENTRE, GLOW_RADII);
    let shape = format!(
        r#"<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill-opacity="{GLOW_OPACITY}"/>"#
    );
    draw::soft((WIDTH, HEIGHT), &shape, GLOW, GLOW_BLUR)
}

/// One phone with its left edge at `left`: body, status bar and the
/// screen.
fn phone(canvas: &mut Canvas, fonts: &Fonts, screen: &RgbaImage, left: i32) -> Result<(), String> {
    canvas.svg(&body(left))?;

    // Status bar, tinted like the screen's top bar.
    let (x, y) = (left + BEZEL_X as i32, PHONE_TOP + BEZEL_TOP as i32);
    canvas.svg(&status(fonts, screen, (x, y)))?;

    // The screen, opaque as recorded.
    let mut opaque = screen.clone();
    opaque.pixels_mut().for_each(|pixel| pixel[3] = u8::MAX);
    let shot = draw::shrink(&opaque, SCREEN_W, SCREEN_H);
    canvas.picture(&shot, x, y + STATUS_H as i32)
}

/// The blurred shadow below a phone, with a margin for the blur on every
/// side.
fn shadow() -> Result<RgbaImage, String> {
    let margin = SHADOW_MARGIN as u32;
    let size = (PHONE_W + 2 * margin, PHONE_H + 2 * margin);
    let drop = SHADOW_MARGIN + SHADOW_DROP;
    let shape = format!(
        r#"<rect x="{margin}" y="{drop}" width="{}" height="{}" rx="{SHADOW_RADIUS}" fill-opacity="{SHADOW_OPACITY}"/>"#,
        PHONE_W + 1,
        PHONE_H + 1,
    );
    draw::soft(size, &shape, [0, 0, 0], SHADOW_BLUR)
}

/// The phone body: rims, earpiece, camera and the lower speaker.
fn body(left: i32) -> String {
    let (w, h) = (PHONE_W as f32, PHONE_H as f32);
    let centre = w / 2.0;
    format!(
        r#"<g transform="translate({left} {PHONE_TOP})">
<rect x="1" y="1" width="{}" height="{}" rx="33" fill="{BODY}" stroke="{RIM}" stroke-width="2"/>
<rect x="6" y="6" width="{}" height="{}" rx="29" fill="none" stroke="{INNER_RIM}" stroke-width="2"/>
<rect x="{}" y="18" width="69" height="6" rx="3" fill="{GRILLE}"/>
<circle cx="{}" cy="20.5" r="6" fill="{LENS}" stroke="{RIM}"/>
<rect x="{}" y="{}" width="97" height="6" rx="3" fill="{GRILLE}"/>
</g>"#,
        w - 2.0,
        h - 2.0,
        w - 12.0,
        h - 12.0,
        centre - 34.0,
        centre - 63.5,
        centre - 48.0,
        h - 21.0,
    )
}

/// The status bar at `(x, y)`: clock, battery and signal in ink that
/// contrasts with the bar.
fn status(fonts: &Fonts, screen: &RgbaImage, (x, y): (i32, i32)) -> String {
    let (bar, ink) = bar_colours(screen);
    let (bar, ink) = (hex(bar), hex(ink));
    let (x, y) = (x as f32, y as f32);
    let (right, bottom) = (x + SCREEN_W as f32 - 8.0, y + STATUS_H as f32);
    let clock = draw::text(
        CLOCK,
        STATUS_FONT,
        &ink,
        (
            x + 8.0,
            y + STATUS_H as f32 / 2.0 + fonts.middle(STATUS_FONT.1, STATUS_FONT.0),
        ),
        Anchor::Start,
    );
    format!(
        r#"<rect x="{x}" y="{y}" width="{SCREEN_W}" height="{STATUS_H}" fill="{bar}"/>{clock}
<rect x="{}" y="{}" width="11" height="7" fill="{ink}"/>
<rect x="{}" y="{}" width="2" height="3" fill="{ink}"/>
<polygon points="{},{} {},{} {},{}" fill="{ink}"/>"#,
        right - 12.0,
        y + 4.0,
        right - 1.0,
        y + 6.0,
        right - 28.0,
        bottom - 3.0,
        right - 17.0,
        bottom - 3.0,
        right - 17.0,
        y + 3.0,
    )
}

/// The status bar's colour, the screen's top rows dimmed, and its ink.
fn bar_colours(screen: &RgbaImage) -> ([u8; 3], [u8; 3]) {
    // Average of the top rows, rounded.
    let rows = TOP_ROWS.min(screen.height());
    let mut sums = [0u64; 3];
    for y in 0..rows {
        for x in 0..screen.width() {
            let pixel = screen.get_pixel(x, y);
            sums.iter_mut()
                .zip(pixel.0)
                .for_each(|(s, c)| *s += u64::from(c));
        }
    }
    let count = u64::from(rows * screen.width()).max(1);
    let top = sums.map(|s| ((s + count / 2) / count) as u8);

    // Dimmed for the bar; dark ink on light screens.
    let bar = top.map(|c| (f32::from(c) * STATUS_DIM).round_ties_even() as u8);
    let brightness = top.iter().map(|&c| u32::from(c)).sum::<u32>();
    let ink = if brightness > 3 * LIGHT {
        DARK_INK
    } else {
        LIGHT_INK
    };
    (bar, ink)
}

/// The text's centre x and the duo's left edge: the duo, the gap and the
/// wider text line are centred on the banner together.
fn heading(duo_w: u32, text_w: f32) -> (f32, i32) {
    let text_x = WIDTH as f32 / 2.0 + (duo_w as f32 + DUO_GAP) / 2.0;
    let duo_x = (text_x - text_w / 2.0 - DUO_GAP - duo_w as f32).round_ties_even();
    (text_x, duo_x as i32)
}

fn text_width(fonts: &Fonts, content: &str, (size, weight): (f32, Weight)) -> f32 {
    fonts.advance(content, weight, size)
}

/// A line of text centred on `(x, middle)`.
fn line(
    fonts: &Fonts,
    content: &str,
    font: (f32, Weight),
    fill: &str,
    (x, middle): (f32, f32),
) -> String {
    let baseline = middle + fonts.middle(font.1, font.0);
    draw::text(content, font, fill, (x, baseline), Anchor::Middle)
}

fn hex([r, g, b]: [u8; 3]) -> String {
    format!("#{r:02x}{g:02x}{b:02x}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fonts;

    /// A screen with a coloured top bar over a light body.
    fn screen(seed: u8) -> RgbaImage {
        RgbaImage::from_fn(SHOT_W, SHOT_H, |x, y| {
            if y < 160 {
                return image::Rgba([seed, 90, 200, 255]);
            }
            let shade = ((x / 30 + y / 40) % 2) as u8 * 30;
            image::Rgba([230 - shade, 235 - shade, 240, 255])
        })
    }

    fn screens() -> [RgbaImage; FAN.len()] {
        std::array::from_fn(|i| screen(40 * i as u8))
    }

    fn duo() -> RgbaImage {
        RgbaImage::from_fn(300, 200, |x, y| {
            let inside = (20..280).contains(&x) && (30..190).contains(&y);
            image::Rgba([250, 120, 30, if inside { 255 } else { 0 }])
        })
    }

    #[test]
    fn geometry_matches_the_recorded_screens() {
        assert_eq!(520, SCREEN_H);
        assert_eq!((264, 610), (PHONE_W, PHONE_H));
        assert!(PHONE_TOP + PHONE_H as i32 <= HEIGHT as i32);
    }

    #[test]
    fn today_is_in_front_and_centred() {
        assert_eq!(Some(&("3_today", WIDTH as i32 / 2)), FAN.last());
    }

    #[test]
    fn renders_are_identical() {
        let fonts = fonts::bundled().unwrap();
        let first = compose(&screens(), &duo(), &fonts).unwrap();
        let second = compose(&screens(), &duo(), &fonts).unwrap();
        assert_eq!((WIDTH, HEIGHT), first.dimensions());
        assert!(first == second);
    }

    #[test]
    fn screens_fill_their_frames() {
        let fonts = fonts::bundled().unwrap();
        let banner = compose(&screens(), &duo(), &fonts).unwrap();

        // The front phone's screen starts below its status bar.
        let left = WIDTH / 2 - PHONE_W / 2 + BEZEL_X;
        let top = PHONE_TOP as u32 + BEZEL_TOP + STATUS_H;
        assert_eq!(&[160, 90, 200], &banner.get_pixel(left + 5, top + 5).0);
    }

    #[test]
    fn heading_is_centred_as_a_group() {
        let (text_x, duo_x) = heading(200, 400.0);
        let group_left = duo_x as f32;
        let group_right = text_x + 200.0;
        assert!((group_left + group_right - WIDTH as f32).abs() <= 1.0);
    }

    #[test]
    fn bar_ink_contrasts() {
        let light = RgbaImage::from_pixel(8, 8, image::Rgba([240, 240, 240, 255]));
        let dark = RgbaImage::from_pixel(8, 8, image::Rgba([20, 60, 120, 255]));
        assert_eq!(([197, 197, 197], DARK_INK), bar_colours(&light));
        assert_eq!(([16, 49, 98], LIGHT_INK), bar_colours(&dark));
    }
}
