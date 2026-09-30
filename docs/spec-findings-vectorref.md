# Spec findings from writing the reference implementation

Date: 2026-09-30. Writing [`tools/vectorref`](../tools/vectorref/) from the spec text alone turned up the gaps below. Each has a **proposed fix**. The reference implements that fix, and **the spec text was updated to match on 2026-09-30**, with each change marked "pending review" in place. If the reviewer chooses differently, the reference changes and the vectors are regenerated.

| ID | Severity | Where | Problem | Proposed fix |
|---|---|---|---|---|
| S-1 | **High** (interoperability) | vault-format §5.2–§5.5, §10.3 | It is not said whether an optional or empty value is **omitted** or **encoded empty** (for example `tags`, `url_hosts`, `linked_items`, `subtitle`, `notes`, `history`). With deterministic encoding and the canonical check, two correct implementations that choose differently **reject each other's data**. | Add a Required/Optional column to every schema table. **Required keys are always present, even if empty. Optional keys are omitted when there is no value, and must never be present with an empty value** (decoders reject that). |
| S-2 | **High** (contradiction) | vault-format §2 against §5.3 | §2 allows only unsigned-integer map keys, but the secret envelope's `values` is a map keyed by 16-byte field ids. No conforming encoder can produce it. | `values` becomes an **array of `[field_id, value]` pairs, sorted by `field_id`**, with no duplicate ids. The integer-keys rule stays universal. |
| S-3 | Medium | vault-format §2 | "Nesting depth" has no counting rule. A backup payload holds six nested containers; if the values inside the innermost one also count, a valid backup exceeds the limit of 6 and is rejected. | **Depth = the number of nested arrays and maps; plain values do not count.** Under this rule a payload is exactly 6, a record at most 3, and a header 2. |
| S-4 | Medium | crypto spec §4.2 | The biometric blob's AAD, `vault_id ‖ ascii("KS-BIO")`, uses a label outside the registry, breaking principle 5 and §3.3. | Register **`vlt/v1/bio-blob`** in §3.3 and use it as the AAD label. (Computed inside Keystore, so no vector changes.) |
| S-5 | Low | vault-format §5.5 | `last_password_check` (key 8) has no default and is marked neither required nor optional. | Required; set to the vault's creation time. |
| S-6 | Low | vault-format §5.6, crypto spec §9.2 | The recovery record stores `rs_id`. If reviewer question Q5 is accepted (salt `rs_id` with `salt_exp`), a fixed `rs_id` no longer exists. | If Q5 is accepted, remove key 2 from the recovery record; the value is computed per export. |
| S-7 | Low | crypto spec §8 | "The first 10 bits of `c`" is clear to most readers, but a byte-order slip is easy. | State it exactly: the checksum is `(c[0] << 2) \| (c[1] >> 6)`, encoded as two symbols, most significant first. |
| S-8 | Low | crypto spec §3.1, §8, §9.2 | `salt = zeros(0)` is passed to HKDF. Libraries differ in whether they accept an empty salt or require "no salt". | Note that both give the same result (RFC 5869: an absent salt is HashLen zero bytes, which HMAC treats like an empty key). The RFC 5869 A.3 vector in `kat/` proves it. |
| S-9 | Low | crypto spec §9.4 | The minimum file length is not stated, so "truncated" handling is implementation-defined. | Before step 3: reject a file shorter than `12 + hdr_len + 24 + 48 + 24 + 16` bytes as `truncated`, before any cryptography. |
| S-10 | Low | crypto spec §11 | The spec requires failures to collapse into one message but defines no error names, so the vectors cannot say *which* check must fail. | Adopt the error names and the "only non-secret failures are distinguishable" rule from [test-vector plan §5.1](test-vector-plan.md#51-error-names) into §11. |

## What the reference confirmed

- The primitives reproduce every published vector: RFC 5869 A.1–A.3 (including the zero-length salt), RFC 4231 cases 1–4, 6 and 7, RFC 9106 §5.3, and draft-irtf-cfrg-xchacha-03 A.3.1.
- Every label in the registry changes the outputs that use it.
- The tamper matrix behaves as specified. A wrong Recovery Key is detected cheaply **before** Argon2id; a wrong password, a flipped bit anywhere in the authenticated parts, and a payload spliced from another backup all fail as the same `auth_failed`.
- The Recovery Key checksum catches a single-character typo and two swapped characters.
