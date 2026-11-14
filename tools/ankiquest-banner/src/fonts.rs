//! Roboto: bundled with the tool (Apache 2.0, see fonts/LICENSE-Roboto.txt),
//! or read from a folder.

use std::fs;
use std::path::Path;

use crate::draw::Fonts;

const BOLD: &str = "Roboto-Bold.ttf";
const REGULAR: &str = "Roboto-Regular.ttf";

/// The Roboto faces built into the tool.
pub(crate) fn bundled() -> Result<Fonts, String> {
    Fonts::new(
        include_bytes!("../fonts/Roboto-Bold.ttf").to_vec(),
        include_bytes!("../fonts/Roboto-Regular.ttf").to_vec(),
    )
}

/// Roboto-Bold.ttf and Roboto-Regular.ttf from `dir`.
pub(crate) fn from_dir(dir: &Path) -> Result<Fonts, String> {
    let read = |name: &str| {
        let path = dir.join(name);
        fs::read(&path).map_err(|e| format!("{}: {e}", path.display()))
    };
    Fonts::new(read(BOLD)?, read(REGULAR)?)
}
