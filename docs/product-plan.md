# Vaultaire product plan

| | |
|---|---|
| Status | **Plan, agreed with the product owner on 2026-10-15.** Nothing in this document is built yet unless it says so. |
| Related | [`threat-model.md`](threat-model.md), [`crypto-spec.md`](crypto-spec.md), [`vault-format.md`](vault-format.md), ADRs [009](adr/ADR-009-card-scanner-ocr.md), [010](adr/ADR-010-backup-drive-saf.md), [011](adr/ADR-011-bank-catalogue.md), [Gate G0](gates/G0.md) |

This page explains in plain language what Vaultaire will do, how it keeps data safe, and in which order it gets built. The specs linked above are the exact rules; where this page and a spec disagree, the spec wins and this page gets fixed.

**Where things stand today:** the security design and file format are written down. The Rust core can encrypt, decrypt and back up records. The HTML prototype is a clickable mockup with no security in it. **There is no Android app yet.** Until an independent review is done, nobody should store real cards or PINs in any build.

---

## 1. What you can keep in Vaultaire

- **Cards:** debit, credit and other cards from any bank, each with its bank, type (debit, credit, other), card name, your own nickname, name on card, number, expiry, network, an optional CVV, a note of your own, and the customer-care number.
- **PINs, each as its own entry:** locker PIN, demat TPIN (CDSL or NSDL), app login PIN, ATM PIN, MPIN, TPIN, door or alarm codes and anything else. A PIN can be linked to the card or account it belongs to.
- **Bank accounts:** bank, account holder, account number, IFSC, branch, customer ID, net-banking user ID and password, transaction password, mobile-app password, MPIN, TPIN, UPI PIN, registered mobile, relationship manager's name and phone number, and the concierge or customer-care number.
- Logins, identity documents, notes, developer keys and memberships, as in the existing design.

The exact fields are in [vault-format §5.10](vault-format.md#510-templates-template-version-1).

## 2. Where your data lives, and who can read it

- **On your phone.** Every entry is encrypted separately with a random 256-bit vault key.
- **That vault key is locked three ways:**
  - your **fingerprint**, through a key kept in the phone's secure hardware;
  - your **vault password together with the phone's screen lock**;
  - your **vault password together with your Recovery Key**. Only this one works on another phone.
- **In your own Google Drive, if you choose.** Vaultaire saves an encrypted backup file there through Android's own file picker. The app itself has no internet access at all. Google stores the file but cannot read it.
- **Never with the developer.** There is no Vaultaire account, server or database. The developer cannot see, recover or reset anything.

### What "signing in with your email" means

There is no Vaultaire login. Your Google account (your email) only lets your phone **find** the backup file in Drive. **It never unlocks it.** Opening a backup always needs your **vault password and your Recovery Key**. If a Google login alone could open the vault, anyone who broke into your Gmail, or Google itself, could read every card and PIN.

### What happens when…

| Situation | What happens |
|---|---|
| You delete the app | Android deletes the vault and the fingerprint keys with it, so the copy on the phone is gone. Reinstall, tap **Restore from backup**, pick the file in Drive, and type your Recovery Key and password. Everything comes back: cards, PINs, bank groups, favourites and notes. |
| You get a new phone | Sign in to the same Google account on the new phone, install Vaultaire, and restore from Drive with your Recovery Key and password. Then set up your fingerprint again: fingerprint keys never leave a phone, by design. |
| Your phone is lost or stolen while locked | The thief sees only encrypted data. Guessing your password needs *that* phone's screen lock for every try. Restore on a new phone from Drive. |
| Someone grabs your phone while Vaultaire is open | Vaultaire locks itself after 2 minutes of no use, 30 seconds after you leave it, and at once when the screen turns off. Card numbers, CVVs and PINs need your fingerprint again anyway. |
| Your Gmail is hacked | The attacker can download the backup file but cannot open it without your password **and** your Recovery Key. They could delete it, so keep a second copy somewhere else if you can. |
| You forget your vault password, but your fingerprint still works | Reset the password with your fingerprint and your Recovery Key. |
| You forget your password **and** lose your Recovery Key | Your phone still opens with your fingerprint until a new fingerprint is added to the phone. **Your backups can never be opened again**, by you or anyone else. Keep the Recovery Key on paper, away from the phone. |
| You never made a backup and lose the phone | The data is gone. Vaultaire keeps reminding you to back up so this does not happen. |

## 3. What already exists, and what is new

| Requirement | Already in the design | What changes |
|---|---|---|
| Every card is its own encrypted record, in one vault, with no developer server | Yes | Nothing |
| Fingerprint to see or copy a card number or CVV (within the last 60 s) | Yes | Nothing |
| CVV only if you switch it on for that card | Yes | Nothing |
| No screenshots, automatic locking, nothing secret in logs or notifications | Yes | Values you revealed are hidden again **the moment you leave the app** |
| Favourites | Yes (a flag on each item) | Shown on the home page and at the top of each bank's list |
| Cards grouped by bank | Only in the mockup, by exact bank name, so "SBI" and "State Bank of India" were two groups | One canonical id per bank from a built-in list of Indian banks, so all spellings land in one group ([ADR-011](adr/ADR-011-bank-catalogue.md)). The mockup's own folders are dropped, so changing a card's bank always moves it |
| Debit or credit, card name, nickname, last four digits, your note | No | New card fields |
| Clipboard clearing | Every copy cleared after 60 s | Card numbers, CVVs, PINs, account numbers and banking passwords cleared after **15 s**; everything else after **30 s** |
| PIN entries, ATM PIN, Indian bank-account fields | No (card PINs were forbidden) | New. An ATM PIN is its own entry, never on the card, and needs your fingerprint **every** time |
| Backup to Google Drive | Manual export existed in the design | Saving to Drive through the file picker, with automatic re-saving ([ADR-010](adr/ADR-010-backup-drive-saf.md)) |
| Camera scanning | No | Front of the card only ([ADR-009](adr/ADR-009-card-scanner-ocr.md)) |
| Coming back to the same bank and list after checkout | No | New |

## 4. How the screens will work

### Home page

```
[ Search cards, banks, items…                 ]

Browse by bank                       [+ Add card]
┌──────────────┐  ┌──────────────────────┐
│ All cards 10 │  │ SBI               5  │
│              │  │ 2 debit · 3 credit   │
└──────────────┘  └──────────────────────┘
┌──────────────┐  ┌──────────────────────┐
│ HDFC Bank  2 │  │ Axis Bank         2  │
└──────────────┘  └──────────────────────┘
┌──────────────────────┐
│ Other / Unassigned 1 │        See all banks ›
└──────────────────────┘

Favourites
★ SBI Cashback · ★ HDFC Millennia

Passwords · Bank accounts · PINs · …
```

- Only banks you have saved cards for appear, with the most cards first. **Other / Unassigned** is always last.
- Each tile shows the bank, how many cards, and how many are debit and credit when there is room.
- Search finds cards by bank (any spelling), nickname, card name, last four digits or your note. It searches only what is already on screen-safe fields; it never searches card numbers, CVVs or PINs, and it keeps no search history.

### A bank's page (for example SBI)

```
SBI · 5 cards                      [+ Add SBI card]
[ All 5 ][ Credit 3 ][ Debit 2 ]

★ SBI Cashback
  Credit · Visa · •••• 1234
  SBI Account Debit
  Debit · RuPay · •••• 5678
  …
```

- Favourites first, then the cards you used most recently.
- **Never shown in a list:** full card numbers, CVVs or PINs.
- Two cards with the same name are told apart by nickname and last four digits.

### A card's page

| Row | How copying works |
|---|---|
| Card number | Hidden. Fingerprint (within 60 s) to show or copy. Copied **without spaces**, ready for checkout. Cleared after 15 s |
| Name on card | Copy; cleared after 30 s |
| Expiry (MM/YY) | Copy; cleared after 30 s |
| Card name / nickname | Copy; cleared after 30 s |
| CVV (only if you stored one) | Fingerprint; cleared after 15 s |
| Your note, network, bank, debit or credit, customer care (Call) | Shown |
| PIN stored separately › | Only if a PIN is linked. Opens the PIN entry; the PIN itself is never shown here |

There is **no "copy everything" button**, on purpose. After each copy a small message says "Copied, clears in 15 s" without showing the value.

### Shopping: the round trip

1. You see an SBI offer on a shopping site.
2. Open Vaultaire → **SBI** → **Credit** → **SBI Cashback** → copy the number → switch to the shop and paste. Do the same for expiry, name and CVV.
3. When you leave Vaultaire, anything you revealed is hidden again at once. After 30 seconds away (your setting), Vaultaire locks.
4. Coming back:
   - **Still unlocked:** you are on the same screen.
   - **Locked:** you touch the fingerprint sensor and land back on **the same bank, the same filter and the same place in the list**.
5. The offer didn't apply? Go back and pick another card.

Vaultaire does **not** fill in checkout forms by itself in this version. It does **not** check offers, and never claims a card qualifies for one. Your notes, such as "Check this card for Amazon offers", are your own words and are labelled that way.

### Adding a card

- **Scan** the front with the camera to fill in the number, expiry and name, or type them.
- You always choose the **bank** (search the built-in list, or add a bank yourself) and whether it is **debit, credit or other**. The scanner never decides these. If it sees a bank's name printed on the card, it suggests it, and you tap to accept.
- A card with no bank goes under **Other / Unassigned** until you set one. Changing a card's bank moves it to that bank's group straight away.

## 5. Security in one page

| Layer | What it does |
|---|---|
| Encryption | Each entry is encrypted on its own (XChaCha20-Poly1305) and bound to its place, so entries cannot be swapped or moved. Short secrets are padded, so a 4-digit PIN looks the same size as a long password |
| Keys | One random vault key, locked by fingerprint hardware, by password + screen lock, and by password + Recovery Key |
| Sensitive fields | Normal (shown), concealed (tap to show), critical (fingerprint within 60 s) and critical-strict (fingerprint **every** time: ATM PINs) |
| Screen | No screenshots or screen recording, blank in the recent-apps list, hidden from other apps' overlays and autofill |
| Clipboard | One field at a time, marked sensitive, cleared after 15 s or 30 s |
| Locking | 2 minutes idle, 30 s in the background, screen off; keys wiped from memory before the lock animation |
| Network | **None.** The app has no internet permission, and CI checks this on every build |
| Backups | Need the vault password **and** the Recovery Key. The file reveals no device name, dates or item counts |
| Camera | Reads only the front of the card, keeps no photo, writes nothing to storage |
| Honesty | The known limits (threat-model §8) are published in the app, in plain language |

## 6. Build order

| Phase | What gets built | Status |
|---|---|---|
| 1. Specs | This plan; changes to the threat model, crypto spec and vault format; ADR-009 to ADR-011 | **Written 2026-10-15; waiting for review at G0** |
| 2. Core (Rust) | Test-freeze guard; bank catalogue and name normaliser; card, PIN and bank-account record rules; Organisation record; backup carrying bank groups; the auto-backup key | Not started |
| 3. App shell (Android) | Unlock and auto-lock; home page with bank tiles, search and favourites; bank page with filters; card page with copy rows; adding and editing; PIN and bank-account entries; returning to the same place after unlock | Not started |
| 4. Backup and restore | Saving to Google Drive, automatic re-saving, restoring on a new phone; real-phone checks (R12) | Not started |
| 5. Scanner | Camera scanning of the card front; checks that it keeps nothing and uses no network (R13) | Not started |
| 6. Gates G2–G4 | Real-phone security tests, leak tests, tamper and lock-out tests, independent review. **Only after this may real data go in** | Not started |

## 7. How each part is built: frozen tests first

The owner's rule (2026-10-15):

1. Each component starts with **tests only**. The developer writes them and the owner reviews them.
2. The owner **freezes** them. Their checksums go into `tests/FROZEN.sha256`, and CI fails if a frozen test file changes.
3. Then, and only then, the code is written, until every test passes.
4. If code and test disagree, **the code changes, never the test.** A test that is genuinely wrong is reported to the owner. Only the owner can unfreeze it, and the change is recorded with the reason.
5. When everything is green, the component is done and the next one starts.

Planned components, in order (phase 2 first):

| # | Component | Tests check, for example |
|---|---|---|
| 1 | Test-freeze guard (`tools/frozen_tests.py`) | An unchanged file passes; a changed or missing file fails; a frozen file cannot be re-frozen with new contents; a damaged checksum list fails |
| 2 | Bank catalogue and name normaliser (`vault-core`) | "SBI", "State Bank of India" and "state bank" give `in.sbi`; unknown names give no match; no two catalogue banks clash |
| 3 | Card, PIN and bank-account record rules (`vault-core`) | Last four must match the number; `card.pin` and PIN fields on cards are refused; ATM PINs must be critical-strict; IFSC format |
| 4 | Organisation record (`vault-core`) | Custom banks round-trip; clashing names are refused; limits |
| 5 | Backup with bank groups (`vault-core`) | Restore keeps bank, type, favourites and notes; merge maps custom banks; the device-only backup location is never exported |
| 6 | Auto-backup key (`vault-core`) | Fresh file key every export; the key is deleted on password change |
| 7 onwards | Android components (phase 3–5), each with its own frozen tests, including the owner's ten checks in §8 | |

## 8. The owner's acceptance checks

These become frozen Android tests in phases 3 and 4, using 5 SBI, 2 HDFC and 3 Axis cards:

1. The groups show SBI 5, HDFC 2, Axis 3, and All cards 10.
2. Opening SBI shows only its five cards.
3. The Debit and Credit filters show the right cards.
4. Two cards with the same name stay distinguishable by nickname and last four digits.
5. Changing a card's bank updates both groups.
6. Deleting the last card of a bank removes that bank's tile.
7. Search finds cards by bank, nickname, card name or last four digits.
8. Backup and restore keep every card field, favourite and bank group.
9. Copying follows the fingerprint and clipboard rules.
10. Coming back from checkout restores the screen only after unlocking.
