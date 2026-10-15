# ADR-010: Backups to the user's Google Drive through the system file picker

- Status: **Proposed**, for review at Gate G0. Cached export key pending reviewer question Q9.
- Date: 2026-10-15

## Context

The owner wants each user's data stored **only with that user**: on the phone, and backed up to **their own Google account**, never to the developer. On a new phone the user signs in with the same Google account and gets everything back ([product plan](../product-plan.md) §2).

The vault already has an encrypted backup format that needs the vault password **and** the Recovery Key (crypto spec §9). The question is how the file reaches Google Drive without breaking invariant I5 (no INTERNET permission).

## Decision

**Use Android's Storage Access Framework (SAF), not a Google API.**

1. **Choosing where:** "Choose backup location" opens the system picker (`ACTION_CREATE_DOCUMENT`, MIME `application/octet-stream`, suggested name `Vaultaire.vaultbackup`). The user picks Google Drive and a folder. The app calls `takePersistableUriPermission` for read and write, and stores the URI in the encrypted settings (vault-format §5.5 `backup_uri`).
2. **Writing:** the core produces the backup bytes in memory. The app writes them to the URI with `openOutputStream(uri, "wt")`. **The Google Drive app**, which has its own network access, uploads the file. Vaultaire never opens a socket.
3. **Auto-backup:** while the vault is unlocked, and after a change has gone 60 s without another change, the app re-exports using the cached export key (crypto spec §9.5). After 3 consecutive failed writes it turns auto-backup off and asks the user to export manually.
4. **Restore:** "Restore from backup" opens `ACTION_OPEN_DOCUMENT`. The user picks the file in Drive and types the Recovery Key and then the password. Import follows crypto spec §9.4 unchanged.
5. **Manual export** ("Export now") stays available, and can also save to the device, USB or any other provider.

**Not used:** Google Sign-In, the Drive REST API, Android Auto Backup (`allowBackup=false` stays), and any server of ours.

## Alternatives considered

| Option | Why not chosen |
|---|---|
| Google Sign-In + Drive API (`appDataFolder`) | Needs INTERNET and Google Play services libraries inside the app, which breaks I5 and adds a large dependency to the trust base. It would put Google credentials in the app process |
| Android Auto Backup to Google | Backs up app files keyed to the device's backup encryption; it cannot carry our two-secret format cleanly, and T09 relies on it being off |
| A developer-run server | Contradicts the product: the developer must hold nothing |
| Manual export only | Safe, but users forget, and a lost phone then means a lost vault (T34) |

## Consequences

- No network code in the app. Drive's behaviour (versioning, upload timing) is outside our control (T38).
- Drive keeps old versions. Each is a complete backup protected by the same two secrets.
- Someone with the user's Google account can delete the backups, though not read them (availability only).
- It depends on the Drive document provider supporting persistable write grants and `"wt"` overwrite (research item **R12**, Gate G2). If it does not, auto-backup is unavailable on that phone, and manual export still works.

## Revisit when

- R12 fails on common phones.
- The owner wants scheduled backups while the vault is locked. That would need the export key available while locked, which is a different security trade-off.
