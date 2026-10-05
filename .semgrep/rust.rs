// Test cases for rust.yaml. Not compiled; never part of the core.

fn printing(secret: &[u8]) {
    // ruleid: no-printing-in-core
    println!("{:?}", secret);
    // ruleid: no-printing-in-core
    dbg!(secret);
}

fn randomness() {
    // ruleid: no-userspace-rng
    let mut rng = rand::thread_rng();
    // ruleid: no-userspace-rng
    let seeded = StdRng::seed_from_u64(42);
    // ok: no-userspace-rng
    getrandom::fill(&mut buf).unwrap();
}

fn unsafety(p: *const u8) {
    // ruleid: no-unsafe-outside-ffi
    let x = unsafe { *p };
}

fn compare(expected_mac: &[u8], computed_mac: &[u8], count: usize) {
    // ruleid: secret-compared-with-eq
    if expected_mac == computed_mac {}
    // ruleid: secret-compared-with-eq
    if header_hmac != stored {}
    // ok: secret-compared-with-eq
    if count == 3 {}
    // ok: secret-compared-with-eq
    let same: bool = expected_mac.ct_eq(computed_mac).into();
    // ok: secret-compared-with-eq
    if header.keys() != [1, 2, 3, 4] {}
    // ruleid: secret-compared-with-eq
    if stored_rs_id == rs_id {}
}
