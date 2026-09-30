# Vaultaire brand assets

`vaultaire-logo.png` is the master logo (1536 × 1024, transparent background). Web-sized copies for the prototype are in `prototype/brand/`.

The name "Vaultaire" and the logo are **not** covered by the GPL licence that applies to the source code. Forks may use the code under the GPL, but must not use the name or logo to present their build as Vaultaire.

## Still to do

- Vector (SVG) version of the V mark, needed for the Android launcher icon (foreground, background and monochrome layers) and for small sizes.
- Confirm the logo's origin and that its creation tool's terms allow commercial use.
- **Name clearance: conflict found (2026-09-30).** A web search, which is not legal clearance, found:
  - **"Vaultaire: Private Photo Vault"**, an iPhone app on the App Store (vaultaire.app): a local-first, encrypted vault. That is the **same product category**, and it holds the `.app` domain. App Store names must be unique, so the iOS version could not use this name, and its owner may have trademark rights from use even without a registration.
  - An older open-source document organiser named Vaultaire on GitHub (different category, lower risk).
  - Decide before publishing: rename (recommended), or get a trademark opinion. The V mark can survive a rename if the new name also starts with V.
  - **Status (2026-09-30): naming deferred; "Vaultaire" stays the working name until then.** The protocol no longer depends on it: crypto labels and Keystore aliases use the fixed identifier `vlt`. Clean candidates so far (web search only, not legal clearance): **Gardea**, **Kofro**, Custodaire, Vigilnaire. Rejected after checks: Vigilinaire (too complicated), VaultCrest, CredVault, CredsVault, CredEssential, Lockwell, Sigillo and others. A full-resolution V-mark crop and a text-wordmark lock screen are kept in `git stash` ("Vigilinaire rename (aborted)") for reuse.
