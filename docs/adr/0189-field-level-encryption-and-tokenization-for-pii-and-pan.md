---
date: 2026-07-23
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [crypto-keys, privacy-gdpr]
summary: "We keep selective column-level protection (pgcrypto birth number, keyed blind index, KMS at rest, crypto-shredding keys) over a blanket field-encryption/tokenization scheme; no real PAN to tokenize (synthetic sandbox PANs)."
---

# ADR-0189 — Field-level encryption and tokenization for PII and PAN

## Context

"Encrypt all PII at the field level" and "tokenize the PAN" are common expectations of a banking
platform, and it is worth stating precisely what the platform does and does not do, because the
honest answer is *selective* protection, not blanket field-level encryption.

Current posture, assembled from existing decisions:

- **Encryption at rest is infrastructure-wide.** EKS etcd and the audit S3/CloudTrail stores are
  envelope-encrypted with AWS KMS keys under OpenTofu with annual rotation (ADR-0172). Postgres data
  and CNPG backups inherit at-rest encryption from the storage layer.
- **The most sensitive PII field is column-encrypted and non-searchable in the clear.** The Czech
  birth number (rodné číslo, RČ) is stored as `parties.birth_number_encrypted` via `pgcrypto`
  (non-deterministic), with a separate **keyed blind index** (HMAC-SHA256 + a Vault-held pepper) for
  deterministic matching — the RČ is never stored searchably in plaintext (ADR-0072).
- **Crypto-shredding / erasure keys exist.** The GDPR data-lifecycle model uses crypto-erasure
  (anonymise-and-cascade) with Transit keys as one erasure mechanism (ADR-0118, ADR-0023); ADR-0172
  is the key inventory of record.
- **There is no real PAN.** Card issuance is virtual-first with **synthetic sandbox PANs** and no
  external processor; authorisation/3DS/PIN are out of scope (ADR-0113). So there is no live primary
  account number, CVV or track data in the system to protect under PCI DSS in the first place.

What is *not* in place is a blanket application-layer scheme that encrypts or tokenizes every PII
column (names, addresses, IBANs, emails) inside the application before it reaches the database. This
ADR decides whether to build one.

## Decision

We **keep the current selective, defense-in-depth posture and do not adopt a blanket application-
layer field-level-encryption or tokenization scheme** at this time:

1. **At-rest encryption (KMS envelope) plus targeted column encryption for the highest-risk field
   (RČ via pgcrypto) plus a blind index for searchable-but-sensitive PII** is the protection model.
   The sensitivity gradient is handled where it matters (the national identifier), not uniformly.

2. **We do not tokenize the PAN, because there is no real PAN.** Cards use synthetic sandbox PANs
   (ADR-0113); tokenizing a value that is already synthetic and non-routable adds ceremony without
   reducing real risk. If a real processor integration is ever added, PAN tokenization / a PCI DSS
   cardholder-data vault becomes a prerequisite of *that* work and would supersede this position.

3. **We do not add per-column application-layer encryption for general PII (names, addresses,
   emails, IBANs) now.** Blanket application-side field encryption defeats indexing, joins, sorting
   and reporting; forces a searchable-encryption or blind-index design onto *every* protected column;
   and largely duplicates protection the KMS at-rest layer already provides against the threat it
   actually addresses (stolen disks/backups). The marginal gain over at-rest + targeted column
   encryption does not justify the query-model and key-management cost for a sandbox.

4. **Key custody follows ADR-0172.** Any field/column key, the RČ pepper and the crypto-shredding
   Transit keys are inventoried there; this ADR does not create a parallel key registry.

The residual, tracked improvement (hence `delivery-status: partial`) is that column-level encryption
is applied only to the RČ today; extending targeted column encryption to a small, explicitly-chosen
set of additional high-sensitivity fields is a reasonable future increment, decided per field, not a
blanket sweep.

## Alternatives considered

- **Blanket application-layer field-level encryption for all PII.** Rejected: it breaks
  search/join/sort, pushes a searchable-encryption or blind-index requirement onto every column, and
  duplicates the KMS at-rest guarantee against the disk/backup-theft threat — high cost, low marginal
  benefit here.
- **Tokenize the PAN via a cardholder-data vault now.** Rejected: there is no real PAN (synthetic
  sandbox PANs, ADR-0113); the control would protect nothing until a real processor exists, at which
  point it is properly scoped as part of that integration.
- **Deterministic (searchable) encryption for all sensitive columns.** Rejected as a general policy:
  deterministic encryption leaks equality and frequency; the blind-index approach (ADR-0072) is a
  stronger, purpose-built answer for the one field that must be matched, and generalising it to every
  column is not warranted.

## Consequences

**Positive**
- The protection model is explicit and proportionate: KMS at rest everywhere, column encryption +
  blind index for the national identifier, crypto-shredding for erasure — no false claim of blanket
  field encryption.
- Queries, reporting and joins over ordinary PII stay simple; no searchable-encryption complexity is
  imposed platform-wide.

**Negative**
- Ordinary PII columns rely on at-rest (KMS) encryption and database access controls, not
  application-layer ciphertext; an attacker with live application/database credentials reads them in
  the clear. This is an accepted trade-off for a sandbox holding no production customer money.
- Extending column encryption to more fields remains manual, per-field work.

**Neutral**
- If a real card processor or a stricter data-classification requirement lands, PAN tokenization and
  broader field encryption are revisited and would supersede this ADR.

## Compliance impact

- PCI DSS: no cardholder data in scope — cards use synthetic sandbox PANs (ADR-0113); PAN
           tokenization is a prerequisite of any future real-processor integration, not of today's
           platform.
- DORA:    not applicable to this data-protection decision beyond the general ICT controls.
- GDPR:    Art. 32 (security of processing) and Art. 5(1)(c) minimisation — the RČ is stored one-way
           (blind index) and column-encrypted; crypto-erasure supports Art. 17 (ADR-0118).
- PSD2:    not applicable.
- CNB:     not applicable to this internal data-protection decision.

## References

- ADR-0072 — client identity unification (RČ pgcrypto column + keyed blind index)
- ADR-0118 — GDPR data lifecycle and retention (crypto-erasure, anonymise-and-cascade)
- ADR-0172 — cryptographic key management and lifecycle (key inventory of record)
- ADR-0113 — card issuance bounded context (synthetic sandbox PANs, no real PAN)
- ADR-0023 — analytics regulatory hardening (crypto-erasure keys)
