# Vaultaire UI prototype

A clickable design mockup of the Android app's main screens, built from blueprint §14–§17.

**This is not product code.** It has no cryptography and no storage, and all its data is sample data. Nothing here may be copied into the app. The real app is native Kotlin/Compose over the Rust core, and none of it gets written before Gate G0.

## Run it

```powershell
python prototype/serve.py
```

Then open <http://localhost:8000>. The server listens on `127.0.0.1` only. Use `python prototype/serve.py 8080` to pick another port.

No install or build step is needed: plain HTML, CSS and JavaScript, with no dependencies. The two fonts (Cormorant Garamond and JetBrains Mono) load from Google Fonts. Offline, the page falls back to Georgia and Consolas.

## What it shows

| Screen | Behaviour worth checking |
|---|---|
| Lock | Guilloché rosette; the ring closes on fingerprint unlock; "unaudited beta" label (Amendment 1) |
| Vault password | Unlock is disabled until something is typed; a dial turns while the (simulated) key derivation runs |
| Vault | Greeting, protections status, wallet strip of cards, search, type filters, dismissible failed-attempts banner, Lock always visible |
| Card (`#/card/1`, `#/card/2`) | **Critical** fields: reveal or copy asks for a fingerprint, then not again for 60 s; one field at a time; auto-hides after 30 s. Five finishes to choose from. Card 1 has a stored CVV, card 2 does not. No PIN field. |
| Login | **Concealed** field: tap to reveal, no fingerprint; strength meter |
| Add item | 8 templates; critical fields marked; passphrase generator; CVV is an opt-in switch on the Card form, off by default, with the risk stated |
| Recovery Kit | Key in 7 groups, then re-type groups 3 and 6 (case, dashes and O/0, I/L/1 forgiven) |
| Settings | Protection options, device protection check, backup and recovery |

Locking (the lock icon) wipes all session state. Leaving a screen, or switching away from the browser tab, re-masks any revealed secret.

## CVV: a changed decision

Blueprint §13 left CVV out. On 2026-09-29 the product owner asked for it. The prototype follows the conditions §13 set for adding it: per-card opt-in, off by default, critical tier (fingerprint to reveal or copy), never autofilled, with an explicit warning. The card PIN is still never stored. This reverses a blueprint decision, so it must be recorded in the threat model and reviewed at Gate G0.

## Known gaps

- Dark "Obsidian" theme only; the light "Ivory" theme is not drawn.
- The passphrase generator uses a 64-word list (~36 bits). The app must use the 7,776-word EFF list (~77 bits).
- The fingerprint prompt is a stand-in. On Android it is the system `BiometricPrompt`.
- "Copied" is simulated; nothing touches the clipboard.
- Most Settings rows and the bank, passport, key, note and membership items have no screens yet.
