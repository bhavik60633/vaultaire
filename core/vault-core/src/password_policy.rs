//! The vault password's strength policy (crypto spec §3.2; threat-model T14; ADR-007).
//!
//! Checked only when a password is set or changed, never when one is typed to unlock, so a
//! stricter policy can never lock anyone out.
//!
//! This is ADR-007's fallback rule, used until the `zxcvbn` review is done:
//! - at least 12 characters (after NFC normalisation);
//! - not a common password, or a common word with digits or symbols around it;
//! - not mostly made of repeats or runs (`aaaa`, `abcd`, `1234`, `qwer`);
//! - a character-set estimate of at least 10¹² guesses.

use unicode_normalization::UnicodeNormalization;

use crate::error::{Result, VaultError};

/// The shortest password allowed, in characters.
pub const MIN_CHARS: usize = 12;
/// log2(10¹²): the smallest estimate allowed.
const MIN_BITS: f64 = 39.86;

/// Common passwords and the words most leaked passwords are built from, lowercase.
const COMMON: &[&str] = &[
    "password",
    "passw0rd",
    "p@ssword",
    "p@ssw0rd",
    "iloveyou",
    "qwerty",
    "qwertyuiop",
    "asdfgh",
    "asdfghjkl",
    "zxcvbnm",
    "letmein",
    "welcome",
    "admin",
    "administrator",
    "monkey",
    "dragon",
    "sunshine",
    "princess",
    "football",
    "cricket",
    "baseball",
    "superman",
    "batman",
    "master",
    "shadow",
    "trustno1",
    "starwars",
    "whatever",
    "freedom",
    "computer",
    "internet",
    "secret",
    "abc123",
    "loveyou",
    "lovely",
    "india",
    "bharat",
    "mumbai",
    "delhi",
    "krishna",
    "ganesh",
    "jaishreeram",
    "omsairam",
    "sairam",
    "changeme",
    "default",
    "login",
    "access",
    "hello",
    "helloworld",
    "pokemon",
    "naruto",
    "michael",
    "jennifer",
    "charlie",
    "jordan",
    "mustang",
    "google",
    "facebook",
    "instagram",
    "samsung",
    "iphone",
    "android",
    "vaultaire",
];

/// Keyboard rows and alphabets in order; neighbours in them form a run.
const SEQUENCES: &[&str] = &[
    "abcdefghijklmnopqrstuvwxyz",
    "01234567890",
    "1234567890",
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm",
];

/// Accepts a password that meets the policy; otherwise `weak_password`.
pub fn check(password: &str) -> Result<()> {
    let normalised: String = password.nfc().collect();
    let chars: Vec<char> = normalised.chars().collect();
    if chars.len() < MIN_CHARS
        || is_common(&normalised)
        || mostly_patterns(&chars)
        || estimate_bits(&chars) < MIN_BITS
    {
        return Err(VaultError::WeakPassword);
    }
    Ok(())
}

/// A common password, or a common word wrapped in digits, symbols and spaces.
fn is_common(password: &str) -> bool {
    let lower = password.to_lowercase();
    let core: String = lower
        .trim_matches(|c: char| !c.is_alphabetic())
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect();
    let compact: String = lower.chars().filter(|c| !c.is_whitespace()).collect();
    COMMON.contains(&core.as_str()) || COMMON.contains(&compact.as_str()) || core.is_empty()
}

/// At least three quarters of the characters belong to repeats or runs of four or more.
fn mostly_patterns(chars: &[char]) -> bool {
    let lower: Vec<char> = chars.iter().flat_map(|c| c.to_lowercase()).collect();
    let mut covered = vec![false; lower.len()];
    let mut start = 0;
    while start < lower.len() {
        let mut end = start + 1;
        while end < lower.len() && linked(lower.get(end - 1), lower.get(end)) {
            end += 1;
        }
        if end - start >= 4 {
            for flag in covered.iter_mut().take(end).skip(start) {
                *flag = true;
            }
        }
        start = end;
    }
    let distinct: std::collections::HashSet<&char> = lower.iter().collect();
    let covered = covered.iter().filter(|c| **c).count();
    distinct.len() <= 3 || covered * 4 >= lower.len() * 3
}

/// Two characters next to each other in a repeat or a sequence (either direction).
fn linked(a: Option<&char>, b: Option<&char>) -> bool {
    let (Some(a), Some(b)) = (a, b) else {
        return false;
    };
    if a == b {
        return true;
    }
    SEQUENCES.iter().any(|seq| {
        let pos = |c: &char| seq.chars().position(|s| s == *c);
        match (pos(a), pos(b)) {
            (Some(x), Some(y)) => x.abs_diff(y) == 1,
            _ => false,
        }
    })
}

/// log2(pool^length), where the pool is the character classes the password uses.
fn estimate_bits(chars: &[char]) -> f64 {
    let mut pool = 0u32;
    if chars.iter().any(char::is_ascii_lowercase) {
        pool += 26;
    }
    if chars.iter().any(char::is_ascii_uppercase) {
        pool += 26;
    }
    if chars.iter().any(char::is_ascii_digit) {
        pool += 10;
    }
    if chars.iter().any(|c| c.is_ascii_punctuation() || *c == ' ') {
        pool += 33;
    }
    if chars.iter().any(|c| !c.is_ascii()) {
        pool += 100;
    }
    let length = u32::try_from(chars.len()).unwrap_or(u32::MAX);
    f64::from(length) * f64::from(pool.max(1)).log2()
}
