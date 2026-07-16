# 04 — Data

Database `openbank_vop`, one table. CNPG cluster `vop-db`, `instances: 2` (ADR-0159). See [the ER diagram](../diagrams/02-er-schema.mmd).

## `vop_verification` — evidence that the control ran

```sql
CREATE TABLE vop_verification (
    id                 UUID         NOT NULL,
    iban_hash          CHAR(64)     NOT NULL,   -- sha256 hex
    supplied_name_hash CHAR(64)     NOT NULL,   -- sha256 hex
    outcome            VARCHAR(16)  NOT NULL,
    no_data_reason     VARCHAR(32),
    requested_by       VARCHAR(255) NOT NULL,
    verified_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_vop_verification PRIMARY KEY (id),
    CONSTRAINT ck_vop_no_data_reason CHECK (
        (outcome = 'NO_DATA' AND no_data_reason IS NOT NULL)
        OR (outcome <> 'NO_DATA' AND no_data_reason IS NULL)
    )
);
```

Migration `V1__init_vop.sql`. Rollback: `DROP TABLE vop_verification;` — no dependents; evidence history is lost on rollback.

The `CHECK` constraint mirrors `VopVerification`'s `init` block. The invariant is stated twice on purpose: the domain enforces it for code paths, the database enforces it for everything else (a migration, a manual fix, a future writer).

## What is NOT here — and why that is the design

**No plaintext IBAN. No plaintext payee name.** Only `sha256` hashes.

Proving a control ran (IPR Art. 5c) does not require retaining every name anyone ever typed into a payment form. That is GDPR Art. 5(1)(c) data minimisation applied literally. The hashes still answer the one question a fraud claim actually asks — *"did we check this name against this IBAN, and what did we say?"* — because the claimant **supplies the inputs**; we hash them and look them up. The evidence is intact; the standing personal-data liability is not created.

This inherits the discipline party-service already set in `V7__party_name_search_trgm.sql`: *"ONLY name columns are indexed/searchable. The birth number is deliberately NOT searchable here."*

**No account-holder name cache.** The authoritative name is party-service's, resolved live per request. A local copy would be a second place for it to go stale — the drift party-service exists to prevent. If latency ever demands a cache, it is an explicit decision with an explicit staleness budget, not a silent denormalisation.

**No foreign keys.** vop-service owns no account and no party. It reads both over REST with an M2M token and stores nothing about them.

## PII classification and retention

| | |
|---|---|
| Classification | `confidential` (`governance.yaml`) |
| Retention | **13 months** — the fraud-claim window |
| **Not** | the 7-year accounting default (ADR-0118) |

A VoP record is evidence a control ran, **not an accounting record**. Applying the accounting retention to it would be exactly the over-retention Art. 5(1)(c) forbids.

> **Open item:** the 13-month sweep has **no scheduler yet**. Follow the ADR-0118 `*RetentionScheduler` pattern (`KycRetentionScheduler`, `CardPiiRetentionScheduler`) when adding it. Until then retention is a stated policy, not an enforced one — tracked in the [threat model](../../../../docs/threat-models/openbank-vop-service.md) §4.

## Indexes

| Index | Serves |
|---|---|
| `ix_vop_verification_lookup` (`iban_hash`, `supplied_name_hash`, `verified_at DESC`) | The fraud-claim query: "what did we answer for this IBAN + name?", newest first. |
| `ix_vop_verification_verified_at` (`verified_at`) | The retention sweep, and per-requester enumeration review — both scan by time. |

The second index exists partly for a detector that **does not exist yet**: a principal whose `no_match` rate spikes is enumerating, not paying. The index is cheap and the query it enables is the one an incident would need at 3am.

## Data VoP reads but never stores

| Source | Field | Why only these |
|---|---|---|
| account-service | `partyId` | The link, nothing else. |
| party-service | `legalName`, `tradingName` | `PartySummary` mirrors **only** the two name fields. VoP compares names; it must not fetch identifiers, birth data, or contact details it has no use for. |

Both DTOs are `@JsonIgnoreProperties(ignoreUnknown = true)` **local mirrors**, never shared types — the upstream services' DTOs can evolve without breaking us, and we cannot accidentally widen what we pull.
