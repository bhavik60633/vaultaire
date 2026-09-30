# Checklist: making the repository public

Part of [G0](G0.md). Work through it in order; publishing cannot be undone (forks, caches and archives keep copies).

## Before the first push

- [x] **Secret scan of the full history and the working folder** with gitleaks 8.30.1 (checksum verified), 2026-09-30: clean. The only hits were two fictional sample values in `prototype/app.js`, now allow-listed by exact string in [`.gitleaks.toml`](../../.gitleaks.toml).
- [x] **Personal details** in tracked files, including PDF and image metadata: none, 2026-09-30.
- [ ] **Name decision.** An existing iPhone app called "Vaultaire: Private Photo Vault" (vaultaire.app) is a local-first encrypted vault, the same category. Rename, or get legal advice, before publishing under this name. See `brand/README.md`.
- [ ] **Commit email.** All commits so far carry a personal Gmail address. Rewrite them to the GitHub noreply address (`<id>+<username>@users.noreply.github.com`) and set it in `git config user.email`. Safe only while nothing has been pushed.
- [ ] Re-run the secret scan after the rewrite (`gitleaks git --config .gitleaks.toml .`).

## Creating the repository (GitHub web)

- [ ] New repository, **Public**, no template, no README, licence or `.gitignore` from GitHub (the repo already has them).
- [ ] Push `main`.

## Settings, right after creating it

- [ ] **Settings → Code security:** Secret scanning **on**, **Push protection on**, Dependabot alerts **on**, CodeQL default setup **on**.
- [ ] **Settings → Rules → New branch ruleset** for `main`: require a pull request, block force pushes, block deletions, require signed commits (once commit signing is set up).
- [ ] **Settings → Actions → General:** allow only actions pinned to a full commit SHA; workflow permissions **read-only** by default.
- [ ] **Settings → General:** turn off Wikis and Projects if unused; keep Issues on for reviewers.
- [ ] Add `SECURITY.md` with how to report a vulnerability privately (GitHub private vulnerability reporting: **Settings → Code security → Private vulnerability reporting → Enable**).
