// SPDX-License-Identifier: AGPL-3.0-only
//! Composes the README banner, docs/graphics/logos/banner_readme.png
//! (1280x741).
//!
//! The banner shows AnkiQuest's title, a tagline, Aki and Ankilope, and five
//! phones in a fan with the app's own screens, left to right: a card being
//! reviewed, the deck list with each deck's daily status and the review
//! heatmap, Today in front, the week leaderboard and a profile. The screens
//! come from AnkiquestBannerScreenshots, which renders them with Robolectric
//! from sample decks and players.
//!
//! Regenerate (from the repository root):
//!
//! ```sh
//! # 1. Record the five screens (360x780dp at xhdpi, 720x1560 px) into
//! #    AnkiDroid/build/outputs/roborazzi/AnkiquestBannerScreenshots/
//! ./gradlew :AnkiDroid:recordRoborazziPlayDebug -Pscreenshot -PreadmeBanner \
//!     --tests '*AnkiquestBannerScreenshots*'
//!
//! # 2. Compose the banner
//! cargo run --release --manifest-path tools/ankiquest-banner/Cargo.toml
//! ```
//!
//! The result is the same on every run: the screens use fixed sample data, a
//! fixed clock for the server screens, a fixed review card and a heatmap
//! pinned to 28 October 2026, and text is set in the Roboto bundled here.
//!
//! The test is skipped unless -PreadmeBanner is given, so the screenshot
//! workflows never run it. .github/workflows/ankiquest_banner.yml runs both
//! steps for every quest-* release tag and opens a pull request against main
//! when the banner changed; `--compare` tells it whether it did.
//!
//! The reviewer's card is a WebView, which Robolectric draws blank: the test
//! draws the card's rendered question and answer natively over it, in the
//! default note type's style.

mod banner;
mod draw;
mod fonts;

use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use image::codecs::png::{CompressionType, FilterType, PngEncoder};
use image::{ImageEncoder, RgbImage, RgbaImage};

/// Default paths, relative to the repository root.
const SHOTS: &str = "AnkiDroid/build/outputs/roborazzi/AnkiquestBannerScreenshots";
const DUO: &str = "AnkiDroid/src/main/res/drawable-nodpi/duo_friends.webp";
const OUT: &str = "docs/graphics/logos/banner_readme.png";

const USAGE: &str = "usage: ankiquest-banner [--shots <dir>] [--font-dir <dir>] [--out <file>]
       ankiquest-banner --compare <new.png> <old.png>

  --shots     folder with the recorded screens
  --font-dir  folder with Roboto-Bold.ttf and Roboto-Regular.ttf, instead
              of the bundled Roboto
  --out       where to write the banner
  --compare   print \"same\" when both images have the same pixels, else
              \"different\"";

/// What the command was asked to do.
enum Command {
    Render {
        shots: PathBuf,
        font_dir: Option<PathBuf>,
        out: PathBuf,
    },
    Compare(PathBuf, PathBuf),
    Help,
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = match parse(&args) {
        Ok(command) => command,
        Err(error) => {
            eprintln!("{error}\n{USAGE}");
            return ExitCode::FAILURE;
        }
    };

    match run(command) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

/// Reads the command line.
fn parse(args: &[String]) -> Result<Command, String> {
    let mut shots = root().join(SHOTS);
    let mut font_dir = None;
    let mut out = root().join(OUT);
    let mut args = args.iter();
    while let Some(arg) = args.next() {
        let mut value = || {
            args.next()
                .map(PathBuf::from)
                .ok_or(format!("{arg} needs a value"))
        };
        match arg.as_str() {
            "--shots" => shots = value()?,
            "--font-dir" => font_dir = Some(value()?),
            "--out" => out = value()?,
            "--compare" => {
                let new = value()?;
                return Ok(Command::Compare(new, value()?));
            }
            "-h" | "--help" => return Ok(Command::Help),
            other => return Err(format!("unknown argument {other}")),
        }
    }
    Ok(Command::Render {
        shots,
        font_dir,
        out,
    })
}

fn run(command: Command) -> Result<(), String> {
    match command {
        Command::Render {
            shots,
            font_dir,
            out,
        } => render(&shots, font_dir.as_deref(), &out),
        Command::Compare(new, old) => {
            let same = load(&new)? == load(&old)?;
            println!("{}", if same { "same" } else { "different" });
            Ok(())
        }
        Command::Help => {
            println!("{USAGE}");
            Ok(())
        }
    }
}

/// Composes the banner from the screens in `shots` and writes it to `out`.
fn render(shots: &Path, font_dir: Option<&Path>, out: &Path) -> Result<(), String> {
    let fonts = match font_dir {
        Some(dir) => fonts::from_dir(dir)?,
        None => fonts::bundled()?,
    };

    // The screens, in the fan's order.
    let mut screens = Vec::with_capacity(banner::FAN.len());
    for (name, _) in banner::FAN {
        let path = shots.join(format!("{name}.png"));
        if !path.is_file() {
            return Err(format!(
                "Missing {}: record AnkiquestBannerScreenshots first (see tools/ankiquest-banner/src/main.rs)",
                path.display()
            ));
        }
        screens.push(load(&path)?);
    }
    let screens: [RgbaImage; banner::FAN.len()] = screens
        .try_into()
        .map_err(|_| "wrong number of screens".to_string())?;

    let duo = load(&root().join(DUO))?;
    let image = banner::compose(&screens, &duo, &fonts)?;
    save(&image, out)?;
    println!("Wrote {}", out.display());
    Ok(())
}

/// The repository root: this crate is in tools/ankiquest-banner.
fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn load(path: &Path) -> Result<RgbaImage, String> {
    let image = image::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(image.into_rgba8())
}

/// Writes a compact PNG.
fn save(image: &RgbImage, path: &Path) -> Result<(), String> {
    let mut bytes = Vec::new();
    let encoder =
        PngEncoder::new_with_quality(&mut bytes, CompressionType::Best, FilterType::Adaptive);
    encoder
        .write_image(
            image,
            image.width(),
            image.height(),
            image::ExtendedColorType::Rgb8,
        )
        .map_err(|e| format!("PNG encoding failed: {e}"))?;
    fs::write(path, bytes).map_err(|e| format!("{}: {e}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn defaults_point_into_the_repository() {
        let Ok(Command::Render { shots, out, .. }) = parse(&[]) else {
            panic!("expected a render");
        };
        assert!(shots.ends_with(SHOTS));
        assert!(out.ends_with(OUT));
        assert!(root().join(DUO).is_file());
    }

    #[test]
    fn options_override_paths() {
        let parsed = parse(&args(&["--shots", "a", "--font-dir", "b", "--out", "c"]));
        let Ok(Command::Render {
            shots,
            font_dir,
            out,
        }) = parsed
        else {
            panic!("expected a render");
        };
        assert_eq!(
            (
                PathBuf::from("a"),
                Some(PathBuf::from("b")),
                PathBuf::from("c")
            ),
            (shots, font_dir, out)
        );
    }

    #[test]
    fn bad_arguments_fail() {
        assert!(parse(&args(&["--out"])).is_err());
        assert!(parse(&args(&["--compare", "a"])).is_err());
        assert!(parse(&args(&["--nope"])).is_err());
    }

    #[test]
    fn missing_screens_are_named() {
        let error = render(
            Path::new("/nonexistent"),
            None,
            Path::new("/nonexistent/out.png"),
        )
        .unwrap_err();
        assert!(error.contains("1_reviewer.png"), "{error}");
    }
}
