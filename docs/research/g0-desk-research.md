# G0 desk research: R1, R2, R5 (cracking cost) and R7

Date: 2026-09-30. Answers the open research items in [threat model §11](../threat-model.md#11-open-research-items) that can be settled without devices. Each answer ends with what changes in the design. Items that still need a real phone are carried to the G3 device matrix, as the threat model already plans.

R3, R4 and R6 are covered by the [Phase-0 results](../spikes/phase0-results.md). R8–R11 are iOS items and stay deferred.

## R1. Does clearing the clipboard from the background work?

**Finding: not reliably with `clearPrimaryClip()` alone.**

- On newer Android versions, `clearPrimaryClip()` called from the background can be a no-op, so the copied secret stays on the clipboard. Bitwarden found and fixed exactly this in its Android app ([bitwarden/android#7169](https://github.com/bitwarden/android/pull/7169)): *"On newer Android versions `clearPrimaryClip()` can be a no-op, so the secret is never evicted."*
- Writing to the clipboard from the background is still permitted; only reading is restricted (Android 10+). Bitwarden's fix relies on that: *"The overwrite is the part that protects the data; the clear is cleanup."*
- Android 13 added an optional system auto-clear after about an hour. It is a backstop at best, off by default and far longer than the vault's 60 s.

**Design consequence (ClipboardGuard):** after 60 s, first **overwrite** the clipboard with an empty clip that is marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE` on API 33+, the `android.content.extra.IS_SENSITIVE` key below that), **then** call `clearPrimaryClip()`. Do it only if the current clip is still the one the vault placed there, so a newer copy by the user is not destroyed. The timer must survive the app being killed (WorkManager). **Still to verify on devices at G3:** Samsung, Xiaomi and Pixel builds.

## R2. Do keyboards honour `EXTRA_IS_SENSITIVE` for clipboard history?

**Finding: the flag is not a history guarantee, and Samsung reportedly ignores it.**

- Android's own guidance describes the flag as hiding the **preview** in the keyboard: it is used to *"visually obfuscate the clipboard content preview within the keyboard GUI"* ([Android developers: secure clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)). It does not promise that keyboards or the OS keep the item out of clipboard history.
- Samsung's One UI clipboard is reported to keep **all** copied text in plain text with no expiry, regardless of the keyboard in use and of the sensitive flag ([report, April 2025](https://cybersecuritynews.com/samsung-one-ui-security-flaw/)). The report names no researcher, but a Samsung community moderator confirmed there is no auto-delete setting. **Treat it as true** until tested.
- Gboard clears its own history after about an hour; that is still far longer than 60 s.

**Design consequence:**

1. Keep copying behind an explicit tap, and behind a fresh biometric for critical fields (already designed).
2. **New:** the first time a user copies a critical field on a Samsung device, show a one-time note: *"Samsung phones keep a clipboard history. After pasting, you may want to clear it in the keyboard's clipboard."*
3. Autofill (V1.1) remains the real fix, since it avoids the clipboard entirely.
4. Update threat-model T11's residual risk: OEM clipboard history can outlive every clearing mechanism the app has.

## R5 (remaining part). What does offline guessing cost an attacker?

This matters only where guessing can leave the phone:

- the **recovery slot**, which also needs the 128-bit Recovery Secret;
- an **exported backup**, which also needs the Recovery Secret;
- the **iOS** password slot (threat model §10).

On Android, the password slot needs this phone's hardware for every guess (T14), so no offline guessing is possible there.

**Evidence:**

| Source | Rate against Argon2id |
|---|---|
| This project, one PC core (Ryzen 5 5600H), at the vault's exact cost (64 MiB, t = 3, p = 1) | 88 ms per guess, about 11 guesses/s per core |
| Estimate for a 96-core server CPU, scaling that measurement | about 1,100 guesses/s |
| Specops research (July 2026), 8 × RTX 5090, rentable for about $5 an hour; parameters not published | about 490 guesses/s ([summary](https://cyberinsider.com/argon2-algorithm-dramatically-slows-password-cracking-by-high-end-gpus/)) |
| Same research, one AMD EPYC server CPU | about 730 guesses/s |

The measurements agree on the order of magnitude: GPUs give almost no advantage against Argon2id at this memory cost.

**Cost of the password policy (at least 10¹² estimated guesses).** Assume a **pessimistic attacker**, 10 times better than every measurement above: 10,000 guesses/s for $5 an hour.

| Target | Time to try 10¹² guesses (one rig) | Rental cost, **expected** (half the space) |
|---|---|---|
| Recovery slot (64 MiB, t = 3) | about 3.2 years | **about $69,000 per vault** |
| Exported backup (128 MiB, t = 4; about 2.7 times the cost per guess, measured on the S20 Ultra) | about 8.6 years | **about $190,000 per backup** |

At the measured rates (about 1,000 guesses/s per $5 an hour), multiply both by 10. And both attacks first need the **Recovery Secret**, which is stored only on paper.

**Design consequence:** the 10¹² policy is adequate for V1: even a pessimistic attacker who already has the Recovery Secret faces a five- to six-figure bill per vault. Keep the policy; do not lower the Argon2id cost. Recheck every year against new hardware (change control, threat model §12).

## R7. Do assistants and on-screen AI read `FLAG_SECURE` windows?

**Finding: the screenshot path is blocked; the autofill path is not.**

- `FLAG_SECURE` stops screen capture through official channels, including the assistant's screen-context capture: protected screens come out black ([How-To Geek](https://www.howtogeek.com/why-screenshots-dont-always-work-especially-in-secure-apps/)). It also blocks what is delivered to the Assist API.
- It does **not** stop **autofill services**, which can read secure activities by design. That is threat-model T31, already mitigated by excluding the vault's views from autofill.
- Gemini's overlay now includes Circle to Search-style screen selection ([Android Authority](https://www.androidauthority.com/gemini-overlay-adding-circle-to-search-more-broadly-3665864/)). That uses the same capture path, so `FLAG_SECURE` should cover it. New assistant features appear frequently, so this needs re-checking.

**Design consequence:**

1. `FLAG_SECURE` on every window.
2. `setRecentsScreenshotEnabled(false)` on API 33+.
3. `setAccessibilityDataSensitive` on API 34+.
4. Autofill exclusion on the root view.
5. Secrets are not placed in view text until revealed.

**Still to verify on a device at G3:** invoke Circle to Search and the Gemini overlay on the vault's reveal screen, and confirm that nothing is captured.
