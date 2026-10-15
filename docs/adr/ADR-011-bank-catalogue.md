# ADR-011: A bundled bank catalogue and name normalisation for bank-grouped cards

- Status: **Proposed**, for review at Gate G0.
- Date: 2026-10-15

## Context

The owner holds many debit and credit cards from several banks and picks one per online purchase, usually to match a bank's offer ([product plan](../product-plan.md) §4). Cards must be grouped by bank, and the same bank must always land in the same group: "SBI", "State Bank of India" and "state bank" are one bank.

The design mockup grouped cards by the free-text issuer, so differently spelled names split into separate groups. The app has no network, so it cannot look banks up online.

## Decision

1. **A bank catalogue ships inside the app.** It lives in `vault-core` (`core/vault-core/data/banks-in.json`, compiled in), versioned with each release, so the same catalogue and normaliser serve the app's screens and the core's backup merge (vault-format §10.4). Each entry has:
   - `id`: `in.<code>`, stable for ever and never reused, for example `in.sbi`;
   - `name`: the short display name, for example "SBI";
   - `full_name`: for example "State Bank of India";
   - `aliases`: other spellings and former names, for example "state bank", "sbi card".

   It covers the scheduled commercial banks listed by the Reserve Bank of India (public sector, private sector, small finance, payments and foreign banks in India), plus card issuers that are not banks, such as American Express. When banks merge, the absorbed bank's id stays valid and its names become aliases of the surviving bank.

2. **Normalisation** of any bank name typed or read:
   1. Unicode NFKC, then case-fold.
   2. Replace every character that is not a letter or digit with a space.
   3. Drop the words `bank`, `ltd`, `limited`, `the`, `of`, `india`, `card`, `cards`, `co`, `corporation`.
   4. Collapse runs of spaces and trim.

   The normalised string is looked up against every catalogue name, full name and alias, normalised the same way. The catalogue is checked at build time so that no two banks share a normalised name.

   If dropping the words leaves an empty string (for example "Bank of India"), the step 2 output is used instead, and the catalogue lists that form as an alias.

3. **User-added banks:** if no catalogue bank matches, the user can add one. It gets an id `u:` + 32 random hex characters and lives in the encrypted Organisation record (vault-format §5.9). It is normalised the same way, so a second spelling finds it.

4. **Grouping** is computed from each card's `bank_id` whenever the list is shown. Groups are never stored. A card with no `bank_id` is shown under **Other / Unassigned**.

5. **The scanner never decides the bank** ([ADR-009](ADR-009-card-scanner-ocr.md)). A card number's first digits (BIN) are not used to guess the issuer: BIN tables are large, change often and would need updates over the network.

## Alternatives considered

| Option | Why not chosen |
|---|---|
| Free-text issuer, grouped by exact string | Splits one bank into several groups |
| Fuzzy matching (edit distance) | Can merge different banks with similar names ("Bank of India" and "Bank of Baroda"); a wrong group is worse than asking the user |
| BIN lookup table | Large, needs updates, wrong for co-branded cards, and the owner said not to infer the bank from the number |
| User-made folders (as in the mockup) | Breaks the rule "change a card's bank and it moves to that bank"; dropped from V1 |

## Consequences

- The catalogue must be maintained with each release. A bank missing from it can still be added by the user.
- Bank ids in records are stable, so renaming a bank's display name in a later catalogue changes no record.
- Unit tests fix the normaliser's behaviour on known spellings, so a catalogue change cannot silently regroup cards.

## Revisit when

- The owner wants other countries' banks: add an `xx.` prefix per country.
