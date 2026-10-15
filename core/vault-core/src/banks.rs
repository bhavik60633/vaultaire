//! The bank catalogue and bank-name normaliser (ADR-011; vault-format §5.1 bank ids).
//!
//! Cards and bank accounts store a bank id, never a free-text bank name, so that "SBI",
//! "State Bank of India" and "state bank" always land in one group. The catalogue ships with the
//! app; banks the user adds themselves (`u:` ids) live in the encrypted Organisation record.
//!
//! Lookups are exact on the normalised name: a name that matches nothing finds no bank. There is
//! no fuzzy matching, because putting a card in the wrong bank's group is worse than asking.

use std::collections::HashMap;
use std::sync::OnceLock;

use unicode_normalization::UnicodeNormalization;

/// Longest bank id, in bytes (vault-format §5.1).
pub const MAX_BANK_ID_LEN: usize = 64;

/// Words dropped by [`normalise`] (ADR-011 decision 2, step 3).
const NOISE_WORDS: [&str; 10] = [
    "bank",
    "ltd",
    "limited",
    "the",
    "of",
    "india",
    "card",
    "cards",
    "co",
    "corporation",
];

/// One catalogue entry. Ids are stable for ever and never reused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Bank {
    /// `in.<code>`.
    pub id: &'static str,
    /// Short display name, as shown on bank tiles.
    pub name: &'static str,
    /// Full legal or common name.
    pub full_name: &'static str,
    /// Other spellings, abbreviations and the names of banks merged into this one.
    pub aliases: &'static [&'static str],
}

/// The bank catalogue: entries plus an index from normalised spelling to entry.
#[derive(Debug)]
pub struct Catalogue {
    banks: &'static [Bank],
    index: OnceLock<HashMap<String, usize>>,
}

static BUNDLED: Catalogue = Catalogue {
    banks: BANKS_IN,
    index: OnceLock::new(),
};

impl Catalogue {
    /// The catalogue compiled into this build.
    pub fn bundled() -> &'static Catalogue {
        &BUNDLED
    }

    /// Every entry, in catalogue order.
    pub fn banks(&self) -> &[Bank] {
        self.banks
    }

    /// The entry with this exact id, if the catalogue has it. Custom (`u:`) ids are never here.
    pub fn get(&self, id: &str) -> Option<&Bank> {
        self.banks.iter().find(|bank| bank.id == id)
    }

    /// The bank whose name, full name or an alias normalises to the same string as `name`.
    pub fn lookup(&self, name: &str) -> Option<&Bank> {
        let key = normalise(name);
        if key.is_empty() {
            return None;
        }
        let position = *self.index().get(&key)?;
        self.banks.get(position)
    }

    fn index(&self) -> &HashMap<String, usize> {
        self.index.get_or_init(|| {
            let mut index = HashMap::new();
            for (position, bank) in self.banks.iter().enumerate() {
                let spellings = [bank.name, bank.full_name]
                    .into_iter()
                    .chain(bank.aliases.iter().copied());
                for spelling in spellings {
                    // The first bank to claim a spelling keeps it; the catalogue tests reject clashes.
                    index.entry(normalise(spelling)).or_insert(position);
                }
            }
            index
        })
    }
}

/// Normalises a bank name for matching (ADR-011 decision 2):
/// NFKC, lowercase, non-alphanumerics to spaces, noise words dropped, spaces collapsed.
/// If dropping the noise words leaves nothing, the words are kept instead ("Bank of India").
pub fn normalise(name: &str) -> String {
    let lowered = name.nfkc().collect::<String>().to_lowercase();
    let spaced: String = lowered
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect();
    let words: Vec<&str> = spaced.split_whitespace().collect();
    let kept: Vec<&str> = words
        .iter()
        .copied()
        .filter(|word| !NOISE_WORDS.contains(word))
        .collect();
    if kept.is_empty() {
        words.join(" ")
    } else {
        kept.join(" ")
    }
}

/// Whether `id` is a well-formed bank id: `in.` + lowercase letters, digits and `_`, or
/// `u:` + 32 lowercase hex characters; at most 64 bytes (vault-format §5.1).
pub fn is_valid_bank_id(id: &str) -> bool {
    if id.len() > MAX_BANK_ID_LEN {
        return false;
    }
    if let Some(code) = id.strip_prefix("in.") {
        return !code.is_empty()
            && code
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_');
    }
    if let Some(hex) = id.strip_prefix("u:") {
        return hex.len() == 32
            && hex
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    }
    false
}

const fn bank(
    id: &'static str,
    name: &'static str,
    full_name: &'static str,
    aliases: &'static [&'static str],
) -> Bank {
    Bank {
        id,
        name,
        full_name,
        aliases,
    }
}

/// Banks and card issuers in India. Merged banks are aliases of the bank that absorbed them.
static BANKS_IN: &[Bank] = &[
    // Public sector banks
    bank(
        "in.sbi",
        "SBI",
        "State Bank of India",
        &["State Bank", "SBI Card"],
    ),
    bank(
        "in.pnb",
        "PNB",
        "Punjab National Bank",
        &["Oriental Bank of Commerce", "OBC", "United Bank of India"],
    ),
    bank(
        "in.bob",
        "Bank of Baroda",
        "Bank of Baroda",
        &["BoB", "Vijaya Bank", "Dena Bank"],
    ),
    bank("in.boi", "Bank of India", "Bank of India", &["BOI"]),
    bank(
        "in.canara",
        "Canara Bank",
        "Canara Bank",
        &["Syndicate Bank"],
    ),
    bank(
        "in.union",
        "Union Bank",
        "Union Bank of India",
        &["Andhra Bank", "Corporation Bank"],
    ),
    bank(
        "in.indian",
        "Indian Bank",
        "Indian Bank",
        &["Allahabad Bank"],
    ),
    bank(
        "in.iob",
        "Indian Overseas Bank",
        "Indian Overseas Bank",
        &["IOB"],
    ),
    bank(
        "in.central",
        "Central Bank of India",
        "Central Bank of India",
        &[],
    ),
    bank("in.bom", "Bank of Maharashtra", "Bank of Maharashtra", &[]),
    bank("in.uco", "UCO Bank", "UCO Bank", &[]),
    bank(
        "in.psb",
        "Punjab & Sind Bank",
        "Punjab & Sind Bank",
        &["Punjab and Sind Bank"],
    ),
    bank("in.idbi", "IDBI Bank", "IDBI Bank", &[]),
    // Private sector banks
    bank("in.hdfc", "HDFC Bank", "HDFC Bank Limited", &[]),
    bank("in.icici", "ICICI Bank", "ICICI Bank Limited", &[]),
    bank("in.axis", "Axis Bank", "Axis Bank Limited", &[]),
    bank("in.kotak", "Kotak", "Kotak Mahindra Bank", &["Kotak Bank"]),
    bank("in.indusind", "IndusInd Bank", "IndusInd Bank", &[]),
    bank("in.yes", "Yes Bank", "YES Bank", &[]),
    bank(
        "in.idfc_first",
        "IDFC FIRST Bank",
        "IDFC FIRST Bank",
        &["IDFC Bank"],
    ),
    bank("in.rbl", "RBL Bank", "RBL Bank", &["Ratnakar Bank"]),
    bank("in.federal", "Federal Bank", "The Federal Bank", &[]),
    bank(
        "in.south_indian",
        "South Indian Bank",
        "South Indian Bank",
        &[],
    ),
    bank("in.karnataka", "Karnataka Bank", "Karnataka Bank", &[]),
    bank("in.kvb", "Karur Vysya Bank", "Karur Vysya Bank", &["KVB"]),
    bank("in.city_union", "City Union Bank", "City Union Bank", &[]),
    bank(
        "in.tmb",
        "Tamilnad Mercantile Bank",
        "Tamilnad Mercantile Bank",
        &["TMB"],
    ),
    bank("in.dcb", "DCB Bank", "DCB Bank", &[]),
    bank("in.csb", "CSB Bank", "CSB Bank", &["Catholic Syrian Bank"]),
    bank("in.dhanlaxmi", "Dhanlaxmi Bank", "Dhanlaxmi Bank", &[]),
    bank(
        "in.jk",
        "J&K Bank",
        "Jammu & Kashmir Bank",
        &["Jammu and Kashmir Bank"],
    ),
    bank("in.bandhan", "Bandhan Bank", "Bandhan Bank", &[]),
    // Small finance banks
    bank(
        "in.au_sfb",
        "AU Small Finance Bank",
        "AU Small Finance Bank",
        &["AU Bank"],
    ),
    bank(
        "in.equitas",
        "Equitas Small Finance Bank",
        "Equitas Small Finance Bank",
        &[],
    ),
    bank(
        "in.ujjivan",
        "Ujjivan Small Finance Bank",
        "Ujjivan Small Finance Bank",
        &[],
    ),
    // Foreign banks in India
    bank(
        "in.sc",
        "Standard Chartered",
        "Standard Chartered Bank",
        &["StanChart"],
    ),
    bank(
        "in.hsbc",
        "HSBC",
        "The Hongkong and Shanghai Banking Corporation",
        &[],
    ),
    bank(
        "in.dbs",
        "DBS Bank",
        "DBS Bank India",
        &["Lakshmi Vilas Bank"],
    ),
    // Payments banks
    bank(
        "in.airtel_pb",
        "Airtel Payments Bank",
        "Airtel Payments Bank",
        &[],
    ),
    bank(
        "in.ippb",
        "India Post Payments Bank",
        "India Post Payments Bank",
        &["IPPB"],
    ),
    // Card issuers that are not banks
    bank("in.amex", "American Express", "American Express", &["Amex"]),
];
