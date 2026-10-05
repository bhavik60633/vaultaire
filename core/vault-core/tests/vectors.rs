//! The Rust core must reproduce every vector in tests/vectors/v1 byte for byte (test-vector plan §9).
#![allow(clippy::indexing_slicing)] // test harness: a panic on a malformed vector is the right outcome

use std::path::PathBuf;

use serde_json::Value as J;
use vault_core::cbor::{self, Limits};
use vault_core::integrity;
use vault_core::keyslot::{self, HwLevel, KdfParams, SlotParams, SlotType};
use vault_core::labels;
use vault_core::prims;
use vault_core::record::{self, EnvelopeKind, RecordKind, Subkeys};
use vault_core::{VaultError, backup, recovery_key};

// ---------- helpers ----------

fn load(name: &str) -> J {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../tests/vectors/v1")
        .join(name);
    serde_json::from_str(
        &std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display())),
    )
    .expect("valid JSON")
}

fn vectors(doc: &J) -> &Vec<J> {
    doc["vectors"].as_array().expect("vectors array")
}

fn h(v: &J) -> Vec<u8> {
    hex::decode(v.as_str().expect("hex string")).expect("valid hex")
}

fn a<const N: usize>(v: &J) -> [u8; N] {
    h(v).try_into()
        .unwrap_or_else(|b: Vec<u8>| panic!("expected {N} bytes, got {}", b.len()))
}

fn text(v: &J) -> String {
    String::from_utf8(h(v)).expect("UTF-8")
}

/// Inputs of a vector with a "base": the base's inputs, overridden by its own.
fn inputs(doc: &J, v: &J) -> serde_json::Map<String, J> {
    let mut merged = serde_json::Map::new();
    if let Some(base) = v.get("base").and_then(J::as_str) {
        let b = vectors(doc)
            .iter()
            .find(|x| x["id"] == base)
            .expect("base vector");
        merged.extend(b["inputs"].as_object().expect("inputs").clone());
    }
    if let Some(own) = v.get("inputs").and_then(J::as_object) {
        merged.extend(own.clone());
    }
    merged
}

fn expect(v: &J) -> &str {
    v["expect"].as_str().expect("expect")
}

fn outcome<T>(r: Result<T, VaultError>) -> String {
    match r {
        Ok(_) => "ok".into(),
        Err(e) => format!("error:{e}"),
    }
}

fn kdf(argon: &J, salt: &J) -> KdfParams {
    KdfParams {
        m: argon["m"].as_u64().expect("m"),
        t: argon["t"].as_u64().expect("t"),
        p: argon["p"].as_u64().expect("p"),
        salt: a(salt),
    }
}

// ---------- published vectors (§12.1) ----------

#[test]
fn kat_hkdf_rfc5869() {
    for v in vectors(&load("kat/hkdf-sha256.json")) {
        let mut out = vec![0u8; v["length"].as_u64().expect("length") as usize];
        prims::hkdf(&h(&v["ikm"]), &h(&v["salt"]), &h(&v["info"]), &mut out);
        assert_eq!(out, h(&v["okm"]), "{}", v["id"]);
    }
}

#[test]
fn kat_hmac_rfc4231() {
    for v in vectors(&load("kat/hmac-sha256.json")) {
        assert_eq!(
            prims::hmac_sha256(&h(&v["key"]), &h(&v["data"])).to_vec(),
            h(&v["hmac_sha256"]),
            "{}",
            v["id"]
        );
    }
}

#[test]
fn kat_xchacha20poly1305() {
    let doc = load("kat/xchacha20-poly1305.json");
    let v = &vectors(&doc)[0];
    let sealed = prims::seal(
        &a(&v["key"]),
        &a(&v["nonce"]),
        &h(&v["plaintext"]),
        &h(&v["aad"]),
    );
    assert_eq!(sealed, [h(&v["ciphertext"]), h(&v["tag"])].concat());
}

// The RFC 9106 vector uses a secret key and associated data, which the vault never does, and
// m = 32 KiB, below the vault's floor; the argon2 crate's own test suite covers it. The vault's
// Argon2id use is covered end to end by the slot and backup vectors below.

// ---------- composition vectors ----------

#[test]
fn labels_registry() {
    let doc = load("labels.json");
    let expected: Vec<Vec<u8>> = vectors(&doc).iter().map(|v| h(&v["bytes"])).collect();
    let mut ours: Vec<Vec<u8>> = labels::ALL.iter().map(|l| l.to_vec()).collect();
    let mut theirs = expected.clone();
    ours.sort();
    theirs.sort();
    assert_eq!(ours, theirs);
}

#[test]
fn password_normalisation() {
    for v in vectors(&load("password-normalisation.json")) {
        let r = prims::encode_password(&text(&v["input_utf8"]));
        assert_eq!(
            outcome(r.as_ref().map(|_| ()).map_err(|e| *e)),
            expect(v),
            "{}",
            v["id"]
        );
        if let Ok(bytes) = r {
            assert_eq!(bytes.to_vec(), h(&v["output"]), "{}", v["id"]);
        }
    }
}

#[test]
fn slot_bio() {
    let doc = load("slot-bio.json");
    for v in vectors(&doc) {
        let i = inputs(&doc, v);
        let (vault_id, slot_id) = (a::<16>(&i["vault_id"]), a::<16>(&i["slot_id"]));
        let kek = keyslot::kek_bio(&a(&i["sk"]), &vault_id);
        if expect(v) == "ok" {
            let params = SlotParams::Bio {
                hw_level: HwLevel::Tee,
                alias: keyslot::keystore_alias(SlotType::Bio, &slot_id),
                iv: a(&h_iv(&v["intermediates"]["params_cbor"])),
                bio_blob: a(&h_blob(&v["intermediates"]["params_cbor"])),
            }
            .encode();
            assert_eq!(params, h(&v["intermediates"]["params_cbor"]));
            assert_eq!(kek.to_vec(), h(&v["intermediates"]["kek_bio"]));
            let ad = keyslot::ad_slot(&vault_id, SlotType::Bio, &slot_id, &params);
            assert_eq!(ad, h(&v["intermediates"]["ad_slot"]));
            assert_eq!(
                keyslot::wrap_vk(&kek, &a(&i["nonce"]), &a(&i["vk"]), &ad),
                h(&v["output"]["wrapped_vk"])
            );
        } else {
            let ad = keyslot::ad_slot(&vault_id, SlotType::Bio, &slot_id, &h(&i["params_cbor"]));
            assert_eq!(
                outcome(keyslot::unwrap_vk(
                    &kek,
                    &a(&i["nonce"]),
                    &h(&i["wrapped_vk"]),
                    &ad
                )),
                expect(v)
            );
        }
    }
}

// The bio params carry opaque Keystore values; read them back from the vector's own encoding.
fn h_iv(params: &J) -> J {
    let p = cbor::decode(&h(params), Limits::HEADER).expect("params");
    J::String(hex::encode(
        p.get(4).and_then(cbor::Value::as_bytes).expect("iv"),
    ))
}
fn h_blob(params: &J) -> J {
    let p = cbor::decode(&h(params), Limits::HEADER).expect("params");
    J::String(hex::encode(
        p.get(5).and_then(cbor::Value::as_bytes).expect("bio_blob"),
    ))
}

#[test]
fn slot_pwd() {
    let doc = load("slot-pwd.json");
    let base = &vectors(&doc)[0];
    let bi = inputs(&doc, base);
    let vault_id = a::<16>(&bi["vault_id"]);
    let slot_id = a::<16>(&bi["slot_id"]);
    let k = kdf(&bi["argon2"], &bi["salt_pwd"]);
    let hw_key = h(&bi["hw_hmac_key"]);
    let params = SlotParams::Pwd {
        hw_level: HwLevel::Tee,
        alias: keyslot::keystore_alias(SlotType::Pwd, &slot_id),
        kdf: k.clone(),
    }
    .encode();
    let mid = &base["intermediates"];
    assert_eq!(params, h(&mid["params_cbor"]));

    let (h_pwd, hw_in) =
        keyslot::pwd_hw_input(&text(&bi["password_utf8"]), &k, &vault_id).expect("derive");
    assert_eq!(h_pwd.to_vec(), h(&mid["h_pwd"]));
    assert_eq!(hw_in.to_vec(), h(&mid["hw_in"]));
    let hw_pwd = prims::hmac_sha256(&hw_key, hw_in.as_ref()); // stand-in for Keystore
    assert_eq!(hw_pwd.to_vec(), h(&mid["hw_pwd"]));
    let kek = keyslot::kek_pwd(&h_pwd, &hw_pwd, &vault_id);
    assert_eq!(kek.to_vec(), h(&mid["kek_pwd"]));
    let ad = keyslot::ad_slot(&vault_id, SlotType::Pwd, &slot_id, &params);
    assert_eq!(ad, h(&mid["ad_slot"]));
    let wrapped = keyslot::wrap_vk(&kek, &a(&bi["nonce"]), &a(&bi["vk"]), &ad);
    assert_eq!(wrapped, h(&base["output"]["wrapped_vk"]));
    let nonce = a(&bi["nonce"]);

    for v in vectors(&doc).iter().skip(1) {
        let i = inputs(&doc, v);
        let (h2, _) =
            keyslot::pwd_hw_input(&text(&i["password_utf8"]), &k, &vault_id).expect("derive");
        let hw2 = prims::hmac_sha256(
            &hw_key,
            &prims::hkdf32(h2.as_ref(), &vault_id, labels::HW_INPUT)[..],
        );
        let kek2 = keyslot::kek_pwd(&h2, &hw2, &vault_id);
        let slot_type = match i.get("slot_type").and_then(J::as_u64) {
            Some(3) => SlotType::Rec,
            _ => SlotType::Pwd,
        };
        let params2 = i
            .get("params_cbor")
            .map(h)
            .unwrap_or_else(|| params.clone());
        let ad2 = keyslot::ad_slot(&vault_id, slot_type, &slot_id, &params2);
        assert_eq!(
            outcome(keyslot::unwrap_vk(&kek2, &nonce, &wrapped, &ad2)),
            expect(v),
            "{}",
            v["id"]
        );
    }
}

#[test]
fn slot_rec() {
    let doc = load("slot-rec.json");
    let base = &vectors(&doc)[0];
    let bi = inputs(&doc, base);
    let vault_id = a::<16>(&bi["vault_id"]);
    let slot_id = a::<16>(&bi["slot_id"]);
    let k = kdf(&bi["argon2"], &bi["salt_rec"]);
    let params = SlotParams::Rec { kdf: k.clone() }.encode();
    assert_eq!(params, h(&base["intermediates"]["params_cbor"]));
    let (kek, h_rec) = keyslot::kek_rec(&text(&bi["password_utf8"]), &k, &a(&bi["rs"]), &vault_id)
        .expect("derive");
    assert_eq!(h_rec.to_vec(), h(&base["intermediates"]["h_rec"]));
    assert_eq!(kek.to_vec(), h(&base["intermediates"]["kek_rec"]));
    let ad = keyslot::ad_slot(&vault_id, SlotType::Rec, &slot_id, &params);
    let wrapped = keyslot::wrap_vk(&kek, &a(&bi["nonce"]), &a(&bi["vk"]), &ad);
    assert_eq!(wrapped, h(&base["output"]["wrapped_vk"]));
    for v in vectors(&doc).iter().skip(1) {
        let i = inputs(&doc, v);
        let (kek2, _) = keyslot::kek_rec(&text(&i["password_utf8"]), &k, &a(&i["rs"]), &vault_id)
            .expect("derive");
        assert_eq!(
            outcome(keyslot::unwrap_vk(&kek2, &a(&i["nonce"]), &wrapped, &ad)),
            expect(v),
            "{}",
            v["id"]
        );
    }
}

#[test]
fn slot_params_range() {
    for v in vectors(&load("slot-params-range.json")) {
        let i = &v["inputs"];
        let r = prims::check_kdf_params(
            i["m"].as_u64().expect("m"),
            i["t"].as_u64().expect("t"),
            i["p"].as_u64().expect("p"),
        );
        assert_eq!(outcome(r), expect(v), "{}", v["id"]);
    }
}

#[test]
fn subkeys() {
    let doc = load("subkeys.json");
    let v = &vectors(&doc)[0];
    let s = Subkeys::derive(&a(&v["inputs"]["vk"]), &a(&v["inputs"]["vault_id"]));
    let o = &v["output"];
    assert_eq!(s.k_idx.to_vec(), h(&o["k_idx"]));
    assert_eq!(s.k_sec.to_vec(), h(&o["k_sec"]));
    assert_eq!(s.k_meta.to_vec(), h(&o["k_meta"]));
    assert_eq!(s.k_hdr.to_vec(), h(&o["k_hdr"]));
}

#[test]
fn padding() {
    for v in vectors(&load("padding.json")) {
        let i = &v["inputs"];
        let bucket = i["bucket"].as_u64().expect("bucket") as usize;
        if let Some(data) = i.get("data") {
            let padded = record::pad(&h(data), bucket);
            assert_eq!(padded, h(&v["output"]), "{}", v["id"]);
            assert_eq!(record::unpad(&padded, bucket).expect("unpad"), &h(data)[..]);
        } else {
            assert_eq!(
                outcome(record::unpad(&h(&i["padded"]), bucket)),
                expect(v),
                "{}",
                v["id"]
            );
        }
    }
}

fn record_kind(n: u64) -> RecordKind {
    match n {
        1 => RecordKind::Item,
        2 => RecordKind::History,
        3 => RecordKind::Settings,
        4 => RecordKind::Recovery,
        5 => RecordKind::SecurityLog,
        _ => RecordKind::Manifest,
    }
}

fn envelope_kind(n: u64) -> EnvelopeKind {
    match n {
        1 => EnvelopeKind::Index,
        2 => EnvelopeKind::Secret,
        _ => EnvelopeKind::Meta,
    }
}

#[test]
fn records() {
    let doc = load("records.json");
    for v in vectors(&doc) {
        let i = inputs(&doc, v);
        let (key, vault_id) = (a::<32>(&i["key"]), a::<16>(&i["vault_id"]));
        let kind = record_kind(i["record_kind"].as_u64().expect("kind"));
        let env = envelope_kind(i["envelope_kind"].as_u64().expect("env"));
        let ad = record::ad_record(
            &vault_id,
            kind,
            &a(&i["record_id"]),
            &a(&i["parent_id"]),
            env,
        );
        if expect(v) == "ok" {
            let mid = &v["intermediates"];
            assert_eq!(ad, h(&mid["ad_record"]), "{}", v["id"]);
            let plain = h(&mid["cbor"]);
            let value =
                cbor::decode(&plain, Limits::RECORD).unwrap_or_else(|e| panic!("{}: {e}", v["id"]));
            assert_eq!(cbor::encode(&value), plain, "{}", v["id"]);
            assert_eq!(record::pad(&plain, env.bucket()), h(&mid["padded"]));
            let ct = record::seal_record(&key, &a(&i["nonce"]), &value, env, &ad);
            assert_eq!(ct, h(&v["output"]["ct"]), "{}", v["id"]);
            assert_eq!(
                record::open_record(&key, &a(&i["nonce"]), &ct, env, &ad).expect("open"),
                value
            );
        } else {
            let ct = i.get("ct").map(h).unwrap_or_else(|| {
                let base = vectors(&doc)
                    .iter()
                    .find(|x| x["id"] == v["base"])
                    .expect("base");
                h(&base["output"]["ct"])
            });
            // The envelope is opened with the key and bucket it was sealed for; only the AD differs.
            let base_env = EnvelopeKind::Secret;
            assert_eq!(
                outcome(record::open_record(
                    &key,
                    &a(&i["nonce"]),
                    &ct,
                    base_env,
                    &ad
                )),
                expect(v),
                "{}",
                v["id"]
            );
        }
    }
}

#[test]
fn header_mac() {
    let doc = load("header-mac.json");
    let base = &vectors(&doc)[0];
    let k_hdr = a::<32>(&base["inputs"]["k_hdr"]);
    let stored = h(&base["output"]["header_mac"]);
    for v in vectors(&doc) {
        let i = inputs(&doc, v);
        let mac =
            integrity::header_mac(&k_hdr, &h(&i["header_cbor"]), &h(&i["keyslot_table_cbor"]));
        assert_eq!(mac.to_vec(), h(&v["output"]["header_mac"]), "{}", v["id"]);
        let ok = integrity::verify_header_mac(
            &k_hdr,
            &h(&i["header_cbor"]),
            &h(&i["keyslot_table_cbor"]),
            &stored,
        );
        assert_eq!(
            if ok { "ok" } else { "error:unlock_failed" },
            expect(v),
            "{}",
            v["id"]
        );
    }
}

#[test]
fn recovery_key() {
    for v in vectors(&load("recovery-key.json")) {
        let i = &v["inputs"];
        if let Some(rs) = i.get("rs") {
            assert_eq!(
                recovery_key::encode(&a(rs)),
                v["output"]["recovery_key"].as_str().expect("key"),
                "{}",
                v["id"]
            );
            if let Some(bits) = v.get("intermediates") {
                assert_eq!(
                    u64::from(recovery_key::checksum_bits(&a(rs))),
                    bits["checksum_10_bits"].as_u64().expect("bits")
                );
            }
        } else {
            let r = recovery_key::decode(i["text"].as_str().expect("text"));
            assert_eq!(outcome(r), expect(v), "{}", v["id"]);
            if let Ok(rs) = r {
                assert_eq!(rs.to_vec(), h(&v["output"]["rs"]), "{}", v["id"]);
            }
        }
    }
}

#[test]
fn backup_file_and_tamper_matrix() {
    let doc = load("backup.json");
    let base = &vectors(&doc)[0];
    let i = &base["inputs"];
    let k = kdf(&i["argon2"], &i["salt_exp"]);
    let payload = cbor::decode(&h(&i["payload_cbor"]), Limits::PAYLOAD).expect("payload");
    let rnd = backup::ExportRandom {
        fk: zeroize::Zeroizing::new(a(&i["fk"])),
        nonce_wrap: a(&i["nonce_wrap"]),
        nonce_payload: a(&i["nonce_payload"]),
    };
    let file = backup::export(&payload, &text(&i["password_utf8"]), &a(&i["rs"]), &k, &rnd)
        .expect("export");
    assert_eq!(
        file,
        h(&base["output"]["file"]),
        "the exported file differs from the reference"
    );
    assert_eq!(
        backup::import(&file, &text(&i["password_utf8"]), &a(&i["rs"]), None).expect("import"),
        payload
    );

    for v in vectors(&doc).iter().skip(1) {
        let t = &v["inputs"];
        let ram = t["total_ram"].as_u64();
        let r = backup::import(
            &h(&t["file"]),
            &text(&t["password_utf8"]),
            &a(&t["rs"]),
            ram,
        );
        assert_eq!(outcome(r), expect(v), "{}", v["id"]);
    }
}

#[test]
fn cbor_profile() {
    for v in vectors(&load("cbor.json")) {
        if expect(v) == "ok" {
            let bytes = h(&v["output"]["cbor"]);
            assert_eq!(
                cbor::encode(&cbor::decode(&bytes, Limits::RECORD).expect("decode")),
                bytes,
                "{}",
                v["id"]
            );
        } else {
            assert_eq!(
                outcome(cbor::decode(&h(&v["inputs"]["cbor"]), Limits::RECORD)),
                expect(v),
                "{}",
                v["id"]
            );
        }
    }
}
