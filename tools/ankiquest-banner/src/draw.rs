//! Drawing: a canvas that takes SVG fragments (resvg) and bitmaps placed at
//! whole pixels, Roboto with its metrics, and Lanczos downscaling.
//!
//! The banner code above this layer never touches resvg or the font files.

use std::sync::Arc;

use image::imageops::{self, FilterType};
use image::{GrayImage, RgbImage, Rgba32FImage, RgbaImage};
use resvg::{tiny_skia, usvg};

/// The family name in the bundled Roboto faces.
const FAMILY: &str = "Roboto";

/// Font weight of a line of text.
#[derive(Clone, Copy, Debug)]
pub(crate) enum Weight {
    Regular,
    Bold,
}

impl Weight {
    fn css(self) -> &'static str {
        match self {
            Weight::Regular => "normal",
            Weight::Bold => "bold",
        }
    }
}

/// Where `x` sits on a line of text.
#[derive(Clone, Copy, Debug)]
pub(crate) enum Anchor {
    Start,
    Middle,
}

impl Anchor {
    fn css(self) -> &'static str {
        match self {
            Anchor::Start => "start",
            Anchor::Middle => "middle",
        }
    }
}

/// Roboto Bold and Regular: the font database resvg draws with, and the raw
/// faces for metrics.
pub(crate) struct Fonts {
    db: Arc<usvg::fontdb::Database>,
    bold: Vec<u8>,
    regular: Vec<u8>,
}

impl Fonts {
    /// Loads the two faces, failing when either is not a font.
    pub(crate) fn new(bold: Vec<u8>, regular: Vec<u8>) -> Result<Self, String> {
        for (name, data) in [("bold", &bold), ("regular", &regular)] {
            ttf_parser::Face::parse(data, 0).map_err(|e| format!("Roboto {name}: {e}"))?;
        }

        let mut db = usvg::fontdb::Database::new();
        db.load_font_data(bold.clone());
        db.load_font_data(regular.clone());
        db.set_sans_serif_family(FAMILY);
        Ok(Fonts {
            db: Arc::new(db),
            bold,
            regular,
        })
    }

    fn face(&self, weight: Weight) -> ttf_parser::Face<'_> {
        let data = match weight {
            Weight::Regular => &self.regular,
            Weight::Bold => &self.bold,
        };
        // Parsed once in `new`, so this cannot fail.
        ttf_parser::Face::parse(data, 0).expect("font parsed in Fonts::new")
    }

    /// The advance width of `text`, without kerning, as the text is drawn.
    pub(crate) fn advance(&self, text: &str, weight: Weight, size: f32) -> f32 {
        let face = self.face(weight);
        let units: u32 = text
            .chars()
            .filter_map(|c| face.glyph_index(c))
            .filter_map(|glyph| face.glyph_hor_advance(glyph))
            .map(u32::from)
            .sum();
        units as f32 * size / f32::from(face.units_per_em())
    }

    /// How far the baseline lies below the middle of ascender and descender.
    /// Both are rounded outward to whole pixels, as FreeType does, so text
    /// centred on a line sits where Pillow's "m" anchor put it.
    pub(crate) fn middle(&self, weight: Weight, size: f32) -> f32 {
        let face = self.face(weight);
        let scale = size / f32::from(face.units_per_em());
        let ascender = (f32::from(face.ascender()) * scale).ceil();
        let descender = (f32::from(face.descender()) * scale).floor();
        (ascender + descender) / 2.0
    }
}

/// A line of text with its baseline at `y`. Kerning is off: the advance
/// widths from `Fonts::advance` are the drawn widths.
pub(crate) fn text(
    content: &str,
    (size, weight): (f32, Weight),
    fill: &str,
    (x, y): (f32, f32),
    anchor: Anchor,
) -> String {
    format!(
        r#"<text x="{x}" y="{y}" font-family="{FAMILY}" font-size="{size}" font-weight="{}" font-kerning="none" fill="{fill}" text-anchor="{}">{content}</text>"#,
        weight.css(),
        anchor.css(),
    )
}

/// An opaque picture being drawn.
pub(crate) struct Canvas {
    pixmap: tiny_skia::Pixmap,
    fonts: Arc<usvg::fontdb::Database>,
}

impl Canvas {
    pub(crate) fn new(width: u32, height: u32, fonts: &Fonts) -> Result<Self, String> {
        let pixmap = tiny_skia::Pixmap::new(width, height).ok_or("empty canvas")?;
        Ok(Canvas {
            pixmap,
            fonts: fonts.db.clone(),
        })
    }

    /// Draws an SVG fragment in canvas coordinates.
    pub(crate) fn svg(&mut self, body: &str) -> Result<(), String> {
        paint(&mut self.pixmap, &self.fonts, body)
    }

    /// Draws `image` with its top-left corner at a whole pixel.
    pub(crate) fn picture(&mut self, image: &RgbaImage, x: i32, y: i32) -> Result<(), String> {
        let mut pixmap =
            tiny_skia::Pixmap::new(image.width(), image.height()).ok_or("empty image")?;
        for (target, source) in pixmap.pixels_mut().iter_mut().zip(image.pixels()) {
            let [r, g, b, a] = source.0;
            *target = tiny_skia::ColorU8::from_rgba(r, g, b, a).premultiply();
        }
        self.pixmap.draw_pixmap(
            x,
            y,
            pixmap.as_ref(),
            &tiny_skia::PixmapPaint::default(),
            tiny_skia::Transform::identity(),
            None,
        );
        Ok(())
    }

    /// The finished picture; the background makes every pixel opaque.
    pub(crate) fn into_rgb(self) -> RgbImage {
        let bytes = self
            .pixmap
            .pixels()
            .iter()
            .flat_map(|pixel| {
                let colour = pixel.demultiply();
                [colour.red(), colour.green(), colour.blue()]
            })
            .collect();
        RgbImage::from_raw(self.pixmap.width(), self.pixmap.height(), bytes).unwrap_or_default()
    }
}

/// Draws an SVG fragment over `pixmap`, in its coordinates.
fn paint(
    pixmap: &mut tiny_skia::Pixmap,
    fonts: &Arc<usvg::fontdb::Database>,
    body: &str,
) -> Result<(), String> {
    let (w, h) = (pixmap.width(), pixmap.height());
    let svg = format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{body}</svg>"#
    );
    let options = usvg::Options {
        font_family: FAMILY.into(),
        fontdb: fonts.clone(),
        ..usvg::Options::default()
    };
    let tree = usvg::Tree::from_str(&svg, &options).map_err(|e| format!("SVG: {e}"))?;
    resvg::render(
        &tree,
        tiny_skia::Transform::identity(),
        &mut pixmap.as_mut(),
    );
    Ok(())
}

/// A soft light or shadow of `size`: the SVG `shape`, whose opacity is the
/// strength, blurred and painted in `colour`.
///
/// The blur is Pillow's GaussianBlur: three box blurs per direction with
/// the edge pixels repeated, so light that reaches the edge stays bright
/// there.
pub(crate) fn soft(
    size: (u32, u32),
    shape: &str,
    colour: [u8; 3],
    radius: f32,
) -> Result<RgbaImage, String> {
    let mut pixmap = tiny_skia::Pixmap::new(size.0, size.1).ok_or("empty light")?;
    paint(&mut pixmap, &Arc::default(), shape)?;
    let alpha = pixmap.pixels().iter().map(|p| p.alpha()).collect();
    let mut mask = GrayImage::from_raw(size.0, size.1, alpha).ok_or("bad light size")?;
    blur(&mut mask, radius);

    let [r, g, b] = colour;
    Ok(RgbaImage::from_fn(size.0, size.1, |x, y| {
        image::Rgba([r, g, b, mask.get_pixel(x, y)[0]])
    }))
}

/// Box blurs per direction in Pillow's GaussianBlur.
const BLUR_PASSES: u32 = 3;

/// Pillow's fixed-point unit for box blur weights.
const UNIT: u64 = 1 << 24;

/// Pillow's GaussianBlur: the box radius for a Gaussian `radius`, then
/// every horizontal pass and every vertical pass.
fn blur(mask: &mut GrayImage, radius: f32) {
    let sigma2 = radius * radius / BLUR_PASSES as f32;
    let length = (12.0 * sigma2 + 1.0).sqrt();
    let whole = ((length - 1.0) / 2.0).floor();
    let part = (2.0 * whole + 1.0) * (whole * (whole + 1.0) - 3.0 * sigma2)
        / (6.0 * (sigma2 - (whole + 1.0) * (whole + 1.0)));
    let radius = whole + part;

    let (w, h) = (mask.width() as usize, mask.height() as usize);
    let mut line = Vec::new();

    // Rows, then columns.
    for y in 0..h {
        line.clear();
        line.extend((0..w).map(|x| mask.as_raw()[y * w + x]));
        (0..BLUR_PASSES).for_each(|_| box_line(&mut line, radius));
        line.iter()
            .enumerate()
            .for_each(|(x, &v)| mask.put_pixel(x as u32, y as u32, image::Luma([v])));
    }
    for x in 0..w {
        line.clear();
        line.extend((0..h).map(|y| mask.as_raw()[y * w + x]));
        (0..BLUR_PASSES).for_each(|_| box_line(&mut line, radius));
        line.iter()
            .enumerate()
            .for_each(|(y, &v)| mask.put_pixel(x as u32, y as u32, image::Luma([v])));
    }
}

/// One box blur of a line with a fractional radius, as Pillow's
/// ImagingLineBoxBlur: whole weights inside, partial ones at both ends,
/// pixels past the ends repeat the end pixels.
fn box_line(line: &mut [u8], radius: f32) {
    let Some(last) = line.len().checked_sub(1) else {
        return;
    };
    let whole = radius as i64;
    let inner = (UNIT as f32 / (radius * 2.0 + 1.0)) as u64;
    let edge = (UNIT - (whole as u64 * 2 + 1) * inner) / 2;
    let at = |i: i64| u64::from(line[i.clamp(0, last as i64) as usize]);

    // A running sum over the whole-weight window.
    let mut sum: u64 = (-whole..=whole).map(at).sum();
    let mut out = Vec::with_capacity(line.len());
    for x in 0..line.len() as i64 {
        let acc = sum * inner + (at(x - whole - 1) + at(x + whole + 1)) * edge;
        out.push(((acc + UNIT / 2) >> 24) as u8);
        sum = sum + at(x + whole + 1) - at(x - whole);
    }
    line.copy_from_slice(&out);
}

/// Lanczos resize in premultiplied alpha, as Pillow does, so transparent
/// pixels do not bleed their colour into the edges.
pub(crate) fn shrink(image: &RgbaImage, width: u32, height: u32) -> RgbaImage {
    if image.dimensions() == (width, height) {
        return image.clone();
    }

    // Premultiply, resize, then divide by alpha again.
    let mut linear = Rgba32FImage::from(image::DynamicImage::ImageRgba8(image.clone()));
    for pixel in linear.pixels_mut() {
        let alpha = pixel[3];
        pixel.0[..3].iter_mut().for_each(|c| *c *= alpha);
    }
    let mut small = imageops::resize(&linear, width, height, FilterType::Lanczos3);
    for pixel in small.pixels_mut() {
        unpremultiply(&mut pixel.0);
    }
    image::DynamicImage::ImageRgba32F(small).into_rgba8()
}

/// Divides colour by alpha, clamping Lanczos overshoot.
fn unpremultiply(pixel: &mut [f32; 4]) {
    let alpha = pixel[3].clamp(0.0, 1.0);
    pixel[3] = alpha;
    if alpha == 0.0 {
        pixel[..3].fill(0.0);
        return;
    }
    pixel[..3]
        .iter_mut()
        .for_each(|c| *c = (*c / alpha).clamp(0.0, 1.0));
}

/// The image cropped to its pixels with any alpha.
pub(crate) fn trim(image: &RgbaImage) -> RgbaImage {
    let visible: Vec<(u32, u32)> = image
        .enumerate_pixels()
        .filter(|(_, _, pixel)| pixel[3] > 0)
        .map(|(x, y, _)| (x, y))
        .collect();
    let Some(left) = visible.iter().map(|p| p.0).min() else {
        return image.clone();
    };
    let right = visible.iter().map(|p| p.0).max().unwrap_or(left);
    let top = visible.iter().map(|p| p.1).min().unwrap_or(0);
    let bottom = visible.iter().map(|p| p.1).max().unwrap_or(top);
    imageops::crop_imm(image, left, top, right - left + 1, bottom - top + 1).to_image()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trim_drops_transparent_margins() {
        let mut image = RgbaImage::new(10, 8);
        image.put_pixel(3, 2, image::Rgba([1, 2, 3, 255]));
        image.put_pixel(6, 5, image::Rgba([1, 2, 3, 9]));
        assert_eq!((4, 4), trim(&image).dimensions());
    }

    #[test]
    fn blur_keeps_flat_areas() {
        let mut mask = GrayImage::from_pixel(50, 30, image::Luma([70]));
        blur(&mut mask, 16.0);
        assert!(mask.pixels().all(|p| p[0] == 70));
    }

    #[test]
    fn blur_spreads_a_point_evenly() {
        let mut mask = GrayImage::new(41, 41);
        mask.put_pixel(20, 20, image::Luma([255]));
        let mut wide = mask.clone();
        blur(&mut mask, 2.0);
        assert!(mask.get_pixel(20, 20)[0] < 255);
        assert_eq!(mask.get_pixel(18, 20), mask.get_pixel(22, 20));
        assert_eq!(mask.get_pixel(20, 18), mask.get_pixel(18, 20));

        // A wider blur spreads further.
        blur(&mut wide, 6.0);
        assert!(wide.get_pixel(20, 20)[0] < mask.get_pixel(20, 20)[0]);
    }

    #[test]
    fn trim_keeps_blank_images() {
        assert_eq!((5, 3), trim(&RgbaImage::new(5, 3)).dimensions());
    }

    #[test]
    fn shrink_keeps_transparent_edges_clean() {
        // A red half beside a transparent green half: no green may show.
        let image = RgbaImage::from_fn(40, 10, |x, _| {
            if x < 20 {
                image::Rgba([255, 0, 0, 255])
            } else {
                image::Rgba([0, 255, 0, 0])
            }
        });
        let small = shrink(&image, 10, 3);
        assert!(small.pixels().all(|p| p[1] == 0));
    }
}
