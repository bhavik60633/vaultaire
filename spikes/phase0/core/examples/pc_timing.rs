// Phase-0 spike: time Argon2id on this PC at the provisional vault settings, for comparison with the phone.
fn main() {
    for _ in 0..3 {
        let t = spike_core::argon2id_timing(65_536, 3, 1).expect("argon2id");
        println!("Argon2id m={} KiB t={} p={}: {} ms", t.memory_kib, t.passes, t.lanes, t.millis);
    }
    println!("AEAD round trip with tamper check: {}", spike_core::aead_roundtrip().expect("aead"));
}
