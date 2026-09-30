# Vaultaire UI prototype

A clickable design mockup of the Android app's main screens, built from blueprint §14–§17.

**This is not product code.** It has no cryptography and no storage, and all its data is sample data. Nothing here may be copied into the app. The real app is native Kotlin/Compose over the Rust core, and none of it gets written before Gate G0.

## Run it

```powershell
python prototype/serve.py
```

Then open <http://localhost:8000>. The server listens on `127.0.0.1` only. Use `python prototype/serve.py 8080` to pick another port.

No install or build step is needed: plain HTML, CSS and JavaScript, with no dependencies. The two fonts (Cormorant Garamond and JetBrains Mono, SIL Open Font License) are bundled in `fonts/`, so nothing is loaded from other sites.

### On a phone (same Wi-Fi)

```powershell
python prototype/serve.py --lan
```

The server prints an address such as `http://192.168.1.2:8000/`; open it on the phone.

- Windows must treat the Wi-Fi as a **Private** network, and Windows Firewall must allow Python on **Private** networks only. Never allow it on Public networks.
- LAN mode answers only devices with private (home-network) addresses.
- Over plain HTTP, the phone shows it as a web page or a home-screen bookmark, not a full-screen installed app. Installation and offline mode (`manifest.webmanifest`, `sw.js`) need HTTPS or `localhost`.

### Android or iPhone version

The prototype shows either platform's version of the design. It follows the device on first visit (an iPhone gets the iPhone version) and remembers the choice. Switch with **Settings → Prototype → Show as**, the **Show as** control in the desktop side panel, or a link such as `http://…:8000/?platform=ios`.

The iPhone version uses Face ID, the passcode, iOS-style back buttons that name their destination, a + in the top bar instead of a floating button, a centred Face ID dialog, iOS-shaped switches, and the iOS device checks. It also states what iPhone cannot do: **screenshots cannot be blocked** there (threat-model §10), so the Recovery Kit screen says so instead of claiming protection.

The real iPhone app is deferred (it needs a Mac). This version only lets the design be judged on an iPhone.

## What it shows

| Screen | Behaviour worth checking |
|---|---|
| Lock | Guilloché rosette; the ring closes on fingerprint unlock; "unaudited beta" label (Amendment 1) |
| Vault password | Unlock is disabled until something is typed; a dial turns while the (simulated) key derivation runs |
| Vault | One scrolling page of category sections in a fixed order: Cards (wallet), Passwords, API keys, Bank accounts, Identity, Notes, Memberships. Up to 3 items per section with "See all"; empty categories hidden; search covers everything. Greeting, protections status, failed-attempts banner, Lock always visible. |
| Home sections (`#/sections`) | The user orders and hides home sections: up and down buttons plus a show switch per section, reset to default. Cards are pinned first. Hidden sections are listed at the bottom of home and stay in search and backups. Opened from "Organise sections" on home or Settings → Appearance. The prototype remembers the layout in this browser only. |
| Category (`#/category/login` and so on) | Every item in one category, a line on how that category is protected, and an Add button for that type |
| Card (`#/card/1`, `#/card/2`) | **Critical** fields: reveal or copy asks for a fingerprint, then not again for 60 s; one field at a time; auto-hides after 30 s. Five finishes to choose from. Card 1 has a stored CVV, card 2 does not. No PIN field. |
| Item (`#/item/k1` and so on) | One detail screen for every other type. Each field follows its tier: **normal** shown, **concealed** tap to reveal, **critical** fingerprint to reveal or copy. Passwords show a strength meter. |
| Add item | 8 templates; critical fields marked; passphrase generator; CVV is an opt-in switch on the Card form, off by default, with the risk stated |
| Recovery Kit | Key in 7 groups, then re-type groups 3 and 6 (case, dashes and O/0, I/L/1 forgiven) |
| Settings | Protection options, device protection check, backup and recovery |

Locking (the lock icon) wipes all session state. Leaving a screen, or switching away from the browser tab, re-masks any revealed secret.

## CVV: a changed decision

Blueprint §13 left CVV out. On 2026-09-29 the product owner asked for it. The prototype follows the conditions §13 set for adding it: per-card opt-in, off by default, critical tier (fingerprint to reveal or copy), never autofilled, with an explicit warning. The card PIN is still never stored. This reverses a blueprint decision, so it must be recorded in the threat model and reviewed at Gate G0.

## Known gaps

- Dark "Obsidian" theme only; the light "Ivory" theme is not drawn.
- The passphrase generator uses a 64-word list (~36 bits). The app must use the 7,776-word EFF list (~77 bits).
- The fingerprint and Face ID prompts are stand-ins. The apps use the system prompts (`BiometricPrompt` on Android, `LocalAuthentication` on iPhone).
- "Copied" is simulated; nothing touches the clipboard.
- Most Settings rows and the bank, passport, key, note and membership items have no screens yet.
