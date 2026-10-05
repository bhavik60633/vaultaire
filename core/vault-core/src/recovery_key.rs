//! The Recovery Key: the Recovery Secret written for humans (crypto spec §8).

use crate::error::{Result, VaultError};
use crate::labels;
use crate::prims;

const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// §8 step 3: the first 10 bits of `HKDF(RS, zeros(0), label, 2)`, most significant first (S-7).
pub fn checksum_bits(rs: &[u8; 16]) -> u16 {
    let mut c = [0u8; 2];
    prims::hkdf(rs, &[], labels::RK_CHECKSUM, &mut c);
    u16::from_be_bytes(c) >> 6
}

fn symbol(index: u32) -> char {
    char::from(ALPHABET.get(index as usize & 31).copied().unwrap_or(b'0'))
}

/// §8 encoding: 26 symbols for `0b00 ‖ RS`, 2 checksum symbols, in groups of 4 joined by `-`.
pub fn encode(rs: &[u8; 16]) -> String {
    let n = u128::from_be_bytes(*rs);
    let mut symbols: Vec<char> = (0..26)
        .rev()
        .map(|i| symbol(((n >> (5 * i)) & 31) as u32))
        .collect();
    let cs = u32::from(checksum_bits(rs));
    symbols.push(symbol(cs >> 5));
    symbols.push(symbol(cs & 31));
    symbols
        .chunks(4)
        .map(|group| group.iter().collect::<String>())
        .collect::<Vec<_>>()
        .join("-")
}

/// §8 decoding. `RkInvalid` for wrong characters or length, `RkChecksum` for a typo.
pub fn decode(text: &str) -> Result<[u8; 16]> {
    let cleaned: Vec<u8> = text
        .chars()
        .filter(|c| *c != ' ' && *c != '-')
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            other => other,
        })
        .map(|c| u8::try_from(c).unwrap_or(0))
        .collect();
    if cleaned.len() != 28 || !matches!(cleaned.first(), Some(b'0'..=b'7')) {
        return Err(VaultError::RkInvalid);
    }
    let mut values = Vec::with_capacity(28);
    for byte in &cleaned {
        let v = ALPHABET
            .iter()
            .position(|a| a == byte)
            .ok_or(VaultError::RkInvalid)?;
        values.push(v as u128);
    }
    let (body, check) = values.split_at(26);
    let n = body.iter().fold(0u128, |acc, v| acc << 5 | v);
    let rs = n.to_be_bytes();
    let cs = check.iter().fold(0u128, |acc, v| acc << 5 | v);
    if cs != u128::from(checksum_bits(&rs)) {
        return Err(VaultError::RkChecksum);
    }
    Ok(rs)
}
