# Threat model: openbank-libs-legal

Scope: ADR-0381's framework-free revision protocol. No endpoint, persistence, external
transport or production consumer exists yet. This is not the threat model for the
future Legal Process service or its operational integrations (issue #16324).

## Assets and trust boundaries

The effect identity, highest applied revision and release tombstone must remain bound
to the correct case and subject. The caller supplies complete snapshots whose canonical
payload digest it has verified. The library cannot establish issuer competence, valid
service, authentic payloads or legal interpretation from a SHA-256-shaped string.

| Threat | Present mitigation / explicit limitation | Evidence |
| --- | --- | --- |
| Delayed attachment resurrects released effect | Keep RELEASED as a stored revision; older snapshots return STALE. The caller must retain that tombstone. | `LegalEffectRevisionGuard.evaluate`; release-before-attachment test |
| Duplicate delivery repeats money operation | Equal snapshot returns DUPLICATE, separately from APPLY. No financial deduplication is claimed without transactional integration. | `LegalEffectRevisionTest` duplicate and permutation cases |
| Equal revision carries changed contents | Different disposition or content digest returns CONFLICT. | `LegalEffectRevisionTest` conflicting equal revision case |
| Effect reassigned to another person/case | Any mismatch of `LegalEffectIdentity` conflicts, even at a higher revision. | `LegalEffectRevisionTest` higher-revision identity mismatch case |
| One order's release lifts another | Guard compares full effect identity; test applies interleaved independent effects. Storage must key by effect identity. | `LegalEffectRevisionTest` delivery permutations |
| Forged digest or issuer | Not mitigated by the library. Authenticity, canonicalization and legal validation belong at intake before constructing the snapshot. | `LegalEffectRevision` contract |
| Concurrent consumers lose the highest revision | Not mitigated by a pure function. Operational owner must serialize read/check/write with state and outbox. Real-DB concurrency tests are a prerequisite to wiring. | Integration obligation documented in `LegalEffectRevisionGuard` |
| Design metadata presented as compliance | Design checker uses a distinct success marker; evidence mode always refuses until trusted verification exists. It is not a deployed runtime control. | `test_legal_process_contracts.py` readiness and mutation cases |

## Remaining integration requirements

Review authenticated intake, independent legal approval, wrong-person resolution,
protected funds, valid legal transitions, live authority admission, scoped access,
retention, immutable evidence and privacy at each owning service. Do not interpret
APPLY as legal authorization, a committed state change, or permission to remit money.
A higher complete revision is technically admissible even after release; only the
legal owner may decide whether reactivation is legally valid.

The shared module imports platform identifiers only and is kept out of platform core
by the context-package rule. It adds no logging or remote dependency. Unit tests prove
ordering behavior; they do not prove bank-wide enforcement or transaction atomicity.
