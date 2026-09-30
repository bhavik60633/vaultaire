# G0 reviewer questions: proposed answers

Date: 2026-09-30. The developer's proposed answers to [crypto spec §14](../crypto-spec.md#14-questions-for-the-g0-reviewer) (Q1–Q8) and [vault format §11](../vault-format.md#11-questions-for-the-g0-reviewer) (F1–F4). They are **proposals**, not decisions: the independent reviewer confirms, changes or rejects each one, and the result is recorded in [G0](G0.md). Where a proposal changes the spec, the change is described here and applied only after the reviewer agrees.

| ID | Proposal in one line | Spec change if accepted |
|---|---|---|
| Q1 | Keep one secret envelope per item | None |
| Q2 | Make the measured Argon2id parameters final | Remove "proposed" from §4.6 |
| Q3 | Adopt the manifest, and **extend it to the settings and recovery records** | §7.2, threat-model T18 |
| Q4 | `hw_in` is sound; treat it as password-equivalent in memory | One sentence in §4.3 |
| Q5 | **Salt `rs_id` with `salt_exp`**: same early check, no linkability | §9.2 |
| Q6 | AD binding plus the range check is sufficient against downgrade | None |
| Q7 | Nothing more at the cryptographic level for CVV | None |
| Q8 | Cap Argon2id memory at 25% of device RAM, checked before any work | §4.6, vault-format import order |
| F1 | Accept plaintext `item_history.item_id` | None |
| F2 | Exclude the security log from backups | None (confirms the proposal) |
| F3 | **Lower `critical_window_s` to at most 120 s** | vault-format §5.5 |
| F4 | Keep normal-tier values in the index envelope | None |

## Crypto spec

### Q1. One secret envelope per item, or one per field?

**Proposal: one per item.** Per-field envelopes would store one row per field, revealing through the row count how many secret fields each item has, and so what kind of item it is. The per-item design already limits exposure: the envelope is opened only when the user reveals or copies a field, the core extracts that one field, and the rest of the plaintext is zeroized immediately, inside the core, without crossing the FFI.

*Trade-off accepted:* revealing a card number briefly decrypts that card's other critical fields (such as an opted-in CVV) in the core's memory for microseconds. An attacker able to read the core's memory at that instant can also read the revealed field, so per-field envelopes would not change the outcome.

### Q2. Final Argon2id parameters?

**Proposal: yes, final.** 64 MiB, t = 3, p = 1 for device slots and 128 MiB, t = 4, p = 1 for backups. The low-end Galaxy A12 unlocks in 798 ms (first run), within the 1 s target ([Phase-0 results](../spikes/phase0-results.md)). The cost to an offline attacker is in the [desk research](../research/g0-desk-research.md#r5-remaining-part-what-does-offline-guessing-cost-an-attacker).

### Q3. Adopt the manifest?

**Proposal: adopt it, and extend it.**

- **Cost is small in practice.** The 250 KiB figure is the worst case at the 10 000-item limit. At a realistic 100–500 items the manifest is about 3–13 KiB, rewritten once per change.
- **Benefit:** it detects deletion or rollback of single records by anyone who can write the file but has no key, for example a backup or cleaner tool, or malware with storage access on a rooted phone.
- **Extension:** the draft covers items and history entries only. The **settings** (kind 3) and **recovery** (kind 4) records should be in it too, each with a revision. Otherwise an attacker could swap in an older valid settings record and silently undo a change the user made to strengthen a setting (for example `critical_window_s` or `password_only`). The security log (kind 5) is append-style and can be included the same way.
- Update threat-model T18 to list the manifest.

### Q4. Is `hw_in = HKDF(H_pwd, …)` sound as the hardware HMAC input?

**Proposal: yes.** HKDF is one-way, so neither Kotlin nor Keystore ever sees `H_pwd`, and `KEK_pwd` needs both `H_pwd` and the hardware output `HW_pwd`.

*Add one sentence to §4.3:* `hw_in` is a deterministic function of the password and salts, so an attacker who captured it could test password guesses offline without the hardware. It must therefore be treated like the password itself: held only for the unlock, zeroized immediately, never logged or stored. Anyone able to read it from memory at that moment could equally capture the typed password, so this adds no new attack.

### Q5. Is `rs_id` linkability between backups acceptable?

**Proposal: remove it at no cost.** Derive the check value with the backup's own salt:

```
rs_id = HKDF(ikm = RS, salt = salt_exp, info = ascii("vlt/v1/rs-id"), L = 8)
```

`salt_exp` is already in the plaintext header, so the importer, who knows RS from the typed Recovery Key or from the vault, computes the same value and still gets the early "this backup was made with a different Recovery Key" message before any Argon2id work. Two backups made with the same Recovery Key now show unrelated `rs_id` values, so a holder of both learns nothing. With a 128-bit RS, the 64-bit check value does not help anyone guess RS.

### Q6. Is the slot AD binding plus the range check enough against downgrade?

**Proposal: yes.** The range check rejects any parameter below the floor **before** Argon2id runs. A change within the range, for example from a device-calibrated higher value down to the floor, changes `slot_params_bytes`, so the AEAD open fails, and without the KEK nobody can make a new valid wrap. The remaining abuse is the opposite direction: parameters pushed to the ceiling to waste time or memory. That is a denial of service, handled by Q8.

### Q7. Anything more for CVV at the cryptographic level?

**Proposal: no.** The CVV lives in the item's secret envelope like every critical field. Its extra protections are policy, and already specified: opt-in per card, off by default, biometric to reveal or copy, never autofilled. Q1's reasoning applies to its brief presence in core memory when the card number is revealed.

### Q8. Memory cap before running Argon2id?

**Proposal:** the app passes the device's total RAM (`ActivityManager.MemoryInfo.totalMem`) to the core, which refuses **before any Argon2id work** if

```
m (in bytes) > min(512 MiB, 25% of total RAM)
```

with a clear message: *"This backup needs more memory than this phone can safely use. Restore it on a phone with more memory."* Examples: a 2 GB phone allows up to about 500 MiB, so 256 MiB backups and the 128 MiB default are fine. The A12 (3.7 GB) allows the full 512 MiB.

Also refuse, with a "try again after closing other apps" message, if the system reports low memory at that moment (`MemoryInfo.lowMemory`).

## Vault format

### F1. Plaintext `item_history.item_id`?

**Proposal: accept.** It reveals how many earlier versions each item has, nothing about content. Moving it inside the envelopes would mean decrypting every history entry to show one item's history. The leak is small and already bound into the associated data, so an entry cannot be moved to another item.

### F2. Security log in backups?

**Proposal: no, as drafted.** The log records this phone's unlock events. Copying it into a file that travels would spread behavioural metadata for no benefit. A restored vault starts a fresh log with a "restored from backup" entry.

### F3. Settings ranges, especially `critical_window_s = 300`?

**Proposal: lower the maximum to 120 s** (allowed 0–120, default 60). After one biometric, every critical field (card numbers, CVVs, account numbers) can be revealed and copied without another check for the length of the window. Five minutes is long enough for a snatched phone to be emptied. The other ranges are reasonable. Auto-lock up to 15 minutes is a user convenience that does not, on its own, bypass the critical-field gate.

### F4. Normal-tier values decrypted at unlock?

**Proposal: keep the line where it is.** Titles, bank names, card last four digits and expiry dates are needed for the home screen, search, card folders and the new expiry watch, all without a biometric per view. They are low-sensitivity on their own: last four digits and expiry are printed on every receipt. They stay only in the unlocked session's memory and are zeroized on lock. The high-value values stay in secret envelopes.
