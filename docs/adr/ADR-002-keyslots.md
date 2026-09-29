# ADR-002: One random vault key wrapped in three keyslots

- Status: **Proposed**, for review at Gate G0.
- Date: 2026-09-29

## Context

The unlock design has to meet all of these at once:

1. **Daily unlock by biometric,** with no typing.
2. **The device passcode must never open the vault on its own.** A thief who watched the owner type their phone PIN, then stole the phone, is a documented pattern. Such a thief can also enrol their own fingerprint.
3. **A fallback when the biometric fails,** is unavailable, or was invalidated by an enrolment change.
4. **Recovery without any server,** account or support desk. Vaultaire holds nothing, so nobody can reset a vault for the user.
5. **Portable encrypted backups,** so a lost phone does not have to mean a lost vault.
6. **No single secret opens everything.** Neither a stolen backup file, nor a photographed Recovery Kit, nor the two together, is enough without the password.

## Decision

Follow the pattern LUKS uses for disk encryption: the data is encrypted under **one random vault key (VK)**, and VK is stored only as separately wrapped copies, one per unlock method. Full details are in [`crypto-spec.md`](../crypto-spec.md) §4.

| Keyslot | Opens with | Bound to the device? | Purpose |
|---|---|---|---|
| **KS-BIO** | A Keystore key that releases a slot key only after a fresh **Class 3 biometric**; invalidated when a new biometric is enrolled | Yes | Daily unlock |
| **KS-PWD** | The vault password (Argon2id) **plus** a hardware HMAC, gated by the **device credential**, computed **once per guess** | Yes | Fallback |
| **KS-REC** | The vault password (Argon2id) **plus** the 128-bit Recovery Secret | No | Recovery on this device; the same construction protects backups |

Every unlock path needs a hardware-enforced factor or two independent secrets. The following are **never** an unlock path: the device passcode alone, a biometric result used as a boolean, the Recovery Key alone, security questions, email or SMS, or support.

## Alternatives considered

| Option | Why rejected |
|---|---|
| **Derive VK from the password** | A password change would mean re-encrypting everything. A biometric could not unlock without the password being stored somewhere. |
| **Biometric as a yes/no gate** in front of a stored key | Trivially bypassed by hooking the callback (for example with Frida). The key must come *from* the biometric-gated hardware. |
| **Device passcode as the fallback** | Makes the passcode a sole unlock path, breaking requirement 2. This was a real flaw in an earlier draft. |
| **Password-only fallback, without a hardware factor** | Anyone who copies `vault.db` could guess passwords offline on GPUs. The per-guess hardware HMAC keeps guessing on this device. |
| **A Recovery-Key-only slot or backup** | A backup file and a photo of the Recovery Kit in the same cloud account would open the vault. Two secrets are required. |
| **Device-bound slots only** | No portable backups, and removing the phone's screen lock would destroy the vault for good. |
| **An oblivious PRF or other custom protocol** (for example on the Secure Enclave) | A novel protocol composition. **Do not implement** without a cryptographer's design and review. |
| **Support-assisted or email recovery** | Creates exactly the social-engineering path the design forbids, and needs a server. |

## Consequences

- **Forgetting the vault password while the biometric is also lost makes the vault unrecoverable.** This is the price of requirement 6. It is stated at onboarding, and softened by a periodic password check (every 14 days by default), which also proves the recovery slot still opens.
- **Removing the phone's screen lock destroys KS-BIO and KS-PWD.** The app detects this and guides the user through KS-REC: set a screen lock, then enter the Recovery Key and password, then re-enrol.
- **A biometric enrolment change invalidates KS-BIO.** The user enters the vault password once to re-enable fingerprint unlock.
- **Anyone whose fingerprint is enrolled on the phone can open KS-BIO.** Onboarding says so; password-only mode is offered.
- **On iOS (later), KS-PWD is weaker.** The Secure Enclave offers no per-guess symmetric operation, so an attacker with the passcode and an exploit can extract the hardware factor once and then guess offline. The password policy (≥ 10¹² estimated guesses) assumes this.
- Unlock and the periodic check each run one Argon2id; changing the password runs two. On the lowest supported device this must stay within the time target in crypto spec §4.6.

## Revisit when

- The G0 reviewer challenges any requirement or slot.
- A "security-delay reset" is designed (biometric + 72-hour wait + second biometric to set a new password and Recovery Key), as a candidate for V1.1. It needs its own threat model and review.
- The iOS phase starts.
