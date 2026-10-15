//! Component 2: the bank catalogue and bank-name normaliser (ADR-011; vault-format §5.1 bank ids).
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/banks.rs`, and the code in `vault_core::banks` is written to pass it.
//!
//! What these tests fix in place:
//! - one bank, many spellings: "SBI", "State Bank of India", "state bank" are the same bank;
//! - different banks never merge, however alike their names ("Bank of India" vs "Bank of Baroda");
//! - unknown names find nothing (no guessing, no fuzzy matching);
//! - bank ids have exactly the two allowed shapes, `in.<code>` and `u:<32 hex>`;
//! - the built-in catalogue is self-consistent: no clashes, every spelling leads back to its bank.

use std::collections::HashMap;

use vault_core::banks::{self, Catalogue};

fn cat() -> &'static Catalogue {
    Catalogue::bundled()
}

fn id_of(name: &str) -> Option<&'static str> {
    cat().lookup(name).map(|bank| bank.id)
}

// ---------- normalise (ADR-011 decision 2) ----------

#[test]
fn normalise_drops_noise_words_case_and_punctuation() {
    let cases = [
        ("State Bank of India", "state"),
        ("  HDFC   Bank Ltd. ", "hdfc"),
        ("ICICI Bank Limited", "icici"),
        ("The Federal Bank Ltd", "federal"),
        ("Punjab & Sind Bank", "punjab sind"),
        ("Kotak Mahindra Bank", "kotak mahindra"),
        ("SBI Card", "sbi"),
        ("American Express", "american express"),
        ("IDFC FIRST Bank", "idfc first"),
        ("Indian Bank", "indian"),
        ("AU Small Finance Bank", "au small finance"),
    ];
    for (input, expected) in cases {
        assert_eq!(banks::normalise(input), expected, "normalise({input:?})");
    }
}

#[test]
fn normalise_applies_nfkc() {
    // Full-width letters, as some keyboards and scanned text produce them.
    assert_eq!(banks::normalise("ＳＢＩ"), "sbi");
    assert_eq!(banks::normalise("ＨＤＦＣ Ｂａｎｋ"), "hdfc");
}

#[test]
fn normalise_falls_back_when_only_noise_words_remain() {
    // Dropping the noise words would leave nothing; step 2's output is used instead.
    assert_eq!(banks::normalise("Bank of India"), "bank of india");
    assert_eq!(banks::normalise("BANK"), "bank");
    assert_eq!(banks::normalise("The Bank, Ltd."), "the bank ltd");
}

#[test]
fn normalise_of_nothing_is_empty() {
    assert_eq!(banks::normalise(""), "");
    assert_eq!(banks::normalise("   "), "");
    assert_eq!(banks::normalise("&-./"), "");
}

#[test]
fn normalise_is_idempotent() {
    for input in [
        "State Bank of India",
        "Bank of India",
        "ＳＢＩ",
        "Punjab & Sind Bank",
        "",
    ] {
        let once = banks::normalise(input);
        assert_eq!(banks::normalise(&once), once, "normalise twice: {input:?}");
    }
}

// ---------- lookup: one bank, many spellings ----------

#[test]
fn every_spelling_of_sbi_is_one_bank() {
    for name in [
        "SBI",
        "sbi",
        "State Bank of India",
        "STATE BANK OF INDIA",
        "state bank",
        "SBI Card",
        "ＳＢＩ",
    ] {
        assert_eq!(id_of(name), Some("in.sbi"), "lookup({name:?})");
    }
}

#[test]
fn common_issuers_resolve_to_their_ids() {
    let cases = [
        ("HDFC Bank Ltd", "in.hdfc"),
        ("HDFC", "in.hdfc"),
        ("ICICI Bank", "in.icici"),
        ("Axis Bank", "in.axis"),
        ("Kotak Mahindra Bank", "in.kotak"),
        ("Kotak", "in.kotak"),
        ("Punjab National Bank", "in.pnb"),
        ("PNB", "in.pnb"),
        ("Bank of Baroda", "in.bob"),
        ("Bank of India", "in.boi"),
        ("Canara Bank", "in.canara"),
        ("Union Bank of India", "in.union"),
        ("IndusInd Bank", "in.indusind"),
        ("Yes Bank", "in.yes"),
        ("IDFC FIRST Bank", "in.idfc_first"),
        ("RBL Bank", "in.rbl"),
        ("Federal Bank", "in.federal"),
        ("AU Small Finance Bank", "in.au_sfb"),
        ("Indian Bank", "in.indian"),
        ("Indian Overseas Bank", "in.iob"),
        ("American Express", "in.amex"),
        ("Amex", "in.amex"),
    ];
    for (name, id) in cases {
        assert_eq!(id_of(name), Some(id), "lookup({name:?})");
    }
}

#[test]
fn similar_names_stay_different_banks() {
    let pairs = [
        ("Bank of India", "Bank of Baroda"),
        ("Bank of India", "State Bank of India"),
        ("Indian Bank", "Indian Overseas Bank"),
        ("Union Bank of India", "Bank of India"),
        ("Punjab National Bank", "Punjab & Sind Bank"),
        ("IDFC FIRST Bank", "HDFC Bank"),
    ];
    for (a, b) in pairs {
        let (ida, idb) = (id_of(a), id_of(b));
        assert!(
            ida.is_some() && idb.is_some(),
            "both must be in the catalogue: {a:?}, {b:?}"
        );
        assert_ne!(ida, idb, "{a:?} and {b:?} must not merge");
    }
}

#[test]
fn unknown_names_find_nothing() {
    for name in [
        "Sunrise Cooperative Credit Society",
        "HDFCC",    // a typo is not guessed
        "Bank",     // too vague to be any one bank
        "of India", // only noise words
        "",
        "   ",
    ] {
        assert_eq!(id_of(name), None, "lookup({name:?})");
    }
}

// ---------- get by id, display names ----------

#[test]
fn get_returns_the_bank_and_its_display_names() {
    let sbi = cat().get("in.sbi").expect("in.sbi is in the catalogue");
    assert_eq!(sbi.id, "in.sbi");
    assert_eq!(sbi.name, "SBI");
    assert_eq!(sbi.full_name, "State Bank of India");

    let hdfc = cat().get("in.hdfc").expect("in.hdfc is in the catalogue");
    assert_eq!(hdfc.name, "HDFC Bank");
}

#[test]
fn get_of_an_unknown_or_custom_id_is_none() {
    assert!(cat().get("in.nope").is_none());
    assert!(cat().get("u:0123456789abcdef0123456789abcdef").is_none()); // custom banks live in the vault
    assert!(cat().get("").is_none());
    assert!(cat().get("IN.SBI").is_none());
}

// ---------- bank ids (vault-format §5.1) ----------

#[test]
fn valid_bank_ids() {
    for id in [
        "in.sbi",
        "in.au_sfb",
        "in.idfc_first",
        "in.a1",
        "u:0123456789abcdef0123456789abcdef",
        "u:ffffffffffffffffffffffffffffffff",
    ] {
        assert!(banks::is_valid_bank_id(id), "{id:?} should be valid");
    }
}

#[test]
fn invalid_bank_ids() {
    for id in [
        "",
        "sbi",
        "in.",
        "IN.SBI",
        "in.SBI",
        "in.s-b",
        "in.s b",
        "in.sbi.",
        "us.chase", // other countries need their own prefix first
        "u:",
        "u:0123456789abcdef0123456789abcde",   // 31 hex
        "u:0123456789abcdef0123456789abcdef0", // 33 hex
        "u:0123456789ABCDEF0123456789ABCDEF",  // uppercase
        "u:0123456789abcdef0123456789abcdeg",  // not hex
        "U:0123456789abcdef0123456789abcdef",
    ] {
        assert!(!banks::is_valid_bank_id(id), "{id:?} should be invalid");
    }
    let long = format!("in.{}", "a".repeat(62)); // 65 bytes: over the 64-byte limit
    assert!(!banks::is_valid_bank_id(&long));
    let longest = format!("in.{}", "a".repeat(61)); // exactly 64 bytes
    assert!(banks::is_valid_bank_id(&longest));
}

// ---------- the catalogue is self-consistent ----------

#[test]
fn catalogue_ids_are_valid_catalogue_ids_and_unique() {
    let mut seen = std::collections::HashSet::new();
    for bank in cat().banks() {
        assert!(
            bank.id.starts_with("in."),
            "{} must be a catalogue id",
            bank.id
        );
        assert!(
            banks::is_valid_bank_id(bank.id),
            "{} must be a valid id",
            bank.id
        );
        assert!(seen.insert(bank.id), "{} listed twice", bank.id);
        assert!(
            !bank.name.is_empty() && !bank.full_name.is_empty(),
            "{} needs both names",
            bank.id
        );
    }
}

#[test]
fn every_name_and_alias_leads_back_to_its_own_bank() {
    for bank in cat().banks() {
        let spellings = [bank.name, bank.full_name]
            .into_iter()
            .chain(bank.aliases.iter().copied());
        for spelling in spellings {
            assert_eq!(
                id_of(spelling),
                Some(bank.id),
                "{spelling:?} (listed for {}) must look up {}",
                bank.id,
                bank.id
            );
        }
    }
}

#[test]
fn no_two_banks_share_a_normalised_spelling() {
    let mut owner: HashMap<String, &str> = HashMap::new();
    for bank in cat().banks() {
        let spellings = [bank.name, bank.full_name]
            .into_iter()
            .chain(bank.aliases.iter().copied());
        for spelling in spellings {
            let key = banks::normalise(spelling);
            assert!(
                !key.is_empty(),
                "{spelling:?} ({}) normalises to nothing",
                bank.id
            );
            if let Some(other) = owner.insert(key.clone(), bank.id) {
                assert_eq!(
                    other, bank.id,
                    "{key:?} is claimed by both {other} and {}",
                    bank.id
                );
            }
        }
    }
}

#[test]
fn catalogue_covers_the_major_indian_issuers() {
    // A floor, not the whole list: the catalogue may hold many more.
    for id in [
        "in.sbi",
        "in.hdfc",
        "in.icici",
        "in.axis",
        "in.kotak",
        "in.pnb",
        "in.bob",
        "in.boi",
        "in.canara",
        "in.union",
        "in.indusind",
        "in.yes",
        "in.idfc_first",
        "in.rbl",
        "in.federal",
        "in.au_sfb",
        "in.indian",
        "in.iob",
        "in.amex",
    ] {
        assert!(cat().get(id).is_some(), "{id} missing from the catalogue");
    }
    assert!(cat().banks().len() >= 19);
}
