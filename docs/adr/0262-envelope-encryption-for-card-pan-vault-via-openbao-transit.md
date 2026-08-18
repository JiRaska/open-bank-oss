---
date: 2026-08-17
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [cards, crypto-keys, secrets, compliance]
summary: "card-issuance-service adopts envelope encryption for the synthetic PAN/CVV vault: a local AES-256-GCM data key wrapped by an OpenBao Transit key-encryption key, giving native rotation and versioning without a per-operation network call."
---

# ADR-0262 — Envelope encryption for card PAN vault via OpenBao Transit

## Context

`openbank-card-issuance-service` encrypts the synthetic PAN/CVV vault (`cards.pan_encrypted`,
`cards.cvv_encrypted`, migration V6, #3) with `AesGcmCardSecretCipher`: AES-256-GCM, a fresh
12-byte IV per value, and a single flat 32-byte key read once at `@Startup` from
`openbank.card.pan-encryption-key` (`OPENBANK_CARD_PAN_ENCRYPTION_KEY`, an OpenBao/ESO-projected
Kubernetes secret). This is correct for what it does — authenticated encryption, key never
committed to the repo, fail-fast boot on a missing/malformed key, no plaintext fallback — but it
has two properties that only surface under a compliance audit rather than in normal operation:

- **No key rotation is possible without data loss.** `decrypt()` is wired to exactly one
  `SecretKeySpec`; the wire format (`base64(IV ‖ ciphertext ‖ tag)`) carries no key identifier or
  version. Replacing the configured key makes every previously-written row permanently
  undecryptable — there is no way to tell the cipher "this row used the old key." PCI DSS 3.6.4
  requires a defined cryptoperiod with the ability to rotate a data-encrypting key; today's design
  cannot satisfy that requirement even in principle, it is not merely unconfigured.
- **The key sits in the application's own K8s secret with no split-knowledge or dual-control
  boundary.** Anyone who can read the `card-issuance` namespace's secrets can read the raw AES key
  and, offline, decrypt every stored PAN. PCI DSS 3.5/3.6 expects key-management operations
  (generation, rotation, access) to sit behind a boundary the application itself does not control.

Card data here is synthetic (a documented ISO/IEC 7812 test BIN, per the V6 migration comment and
the service's threat model), so PCI DSS Cardholder Data Environment scope is not currently
triggered. This ADR is written now, before that changes, because the gap is structural — closing
it later means a migration on live data instead of a design choice made once.

OpenBao is already this platform's secret-management system (every service secret, including
today's flat PAN key, arrives via OpenBao/ESO); it ships a Transit secrets engine built exactly
for key-encryption-key custody, native key rotation, and versioned ciphertext
(`vault:v<N>:<blob>`). Reusing it avoids introducing a second key-management product (e.g. AWS
KMS) for one service.

## Decision

We will change `card-issuance-service`'s PAN/CVV encryption from a single flat key to **envelope
encryption**: a locally-held AES-256-GCM data-encryption key (DEK) does the actual PAN/CVV
encrypt/decrypt exactly as today, but the DEK itself is wrapped by a key-encryption key (KEK) that
lives in an OpenBao Transit key (`transit/keys/card-pan`) and never leaves OpenBao unencrypted.

- At startup, the service holds a wrapped DEK (config/secret-provisioned, analogous to today's
  `pan-encryption-key`). It calls OpenBao Transit's `decrypt` endpoint once to unwrap it into
  memory, then encrypts/decrypts PAN and CVV values locally with the existing
  `AesGcmCardSecretCipher` logic — no per-value network call, no new hot-path dependency on
  OpenBao availability.
- The `CardSecretCipher` port (`encrypt(String): String`, `decrypt(String): String`) is unchanged;
  a new adapter, `OpenBaoEnvelopeCardSecretCipher`, unwraps the DEK via OpenBao Transit at
  `@Startup` and delegates the actual encrypt/decrypt to an `AesGcmCardSecretCipher` instance built
  from it. `openbank.card.key-source` (build-time property) selects between it and today's flat-key
  adapter, so an unconfigured deployment is unaffected.
- **Rotation** happens at the KEK layer: `vault write -f transit/keys/card-pan/rotate` creates a
  new Transit key version. The service then generates a new DEK, wraps it with the now-current
  Transit key version, and switches to it for new writes. Rows encrypted under the previous DEK
  stay decryptable as long as that DEK's wrapped form (and the Transit key version that wrapped
  it) is retained — Transit keeps prior key versions by design, so this requires no new versioning
  scheme in our own wire format.
- Re-encrypting existing rows from an old DEK to the current one is a batch job, following the
  same idempotent, count-only-logging pattern as `CardPanVaultBackfill` (#3) — never a blocking
  migration, never a value in a log line.
- Access to `transit/keys/card-pan`'s `rotate` and `decrypt` (unwrap) capabilities is governed by
  OpenBao ACL policy, not by application code — this is what gives us the split-knowledge/
  dual-control boundary PCI DSS 3.5/3.6 expects, which a Kubernetes-secret-held flat key cannot
  provide by construction.
- `%dev`/`%test` keep an equivalent of today's ephemeral-key escape hatch (a local, per-boot random
  DEK, no OpenBao dependency) so local development and CI are unaffected.

## Alternatives considered

- **Per-value Transit `encrypt`/`decrypt` calls (no local DEK).** Simplest to reason about — every
  PAN/CVV operation is a Transit API call, Transit's own versioned ciphertext handles rotation with
  zero extra code. Rejected as the primary path because it makes OpenBao a hard synchronous
  dependency on `readSecureDetails` and card issuance, adds network latency to both, and couples
  service availability to Transit availability for an operation that today is a pure in-process
  call. Kept as the honest fallback if the envelope approach's added complexity (DEK lifecycle,
  re-encrypt batch job) turns out not to be worth it in practice.
- **AWS KMS envelope encryption instead of OpenBao Transit.** Same envelope shape, different KEK
  custodian. Rejected: this platform has already standardized on OpenBao for every other secret
  card-issuance-service holds (including today's flat PAN key), and introducing a second
  key-management product for one service's KEK adds an operational surface (a second set of
  credentials, a second audit trail, a second thing to keep available) with no compliance benefit
  Transit does not already provide.
- **Keep the flat key, add manual key-id versioning in our own wire format.** Prepend a key-id byte
  to `base64(IV ‖ ciphertext ‖ tag)` and hand-roll a multi-key keystore in
  `AesGcmCardSecretCipher`. Rejected: this reimplements exactly what Transit already does
  correctly (versioned ciphertext, retained prior key versions) and still leaves the KEK itself
  sitting in a Kubernetes secret with no dual-control boundary — it would close the rotation gap
  but not the key-custody gap, for comparable implementation cost to the envelope approach that
  closes both.

## Consequences

**Positive**
- Key rotation becomes possible without any data loss or downtime — the structural blocker
  described in Context is removed.
- Key custody moves behind OpenBao ACL policy instead of a flat Kubernetes secret, giving a real
  split-knowledge/dual-control boundary for the KEK.
- `CardSecretCipher`'s port stays the same; the change is isolated to one adapter
  (`AesGcmCardSecretCipher`'s key source) plus a new `OpenBaoTransitKeyProvider`, so the encrypt/
  decrypt hot path, its tests, and every caller (`CardService`, `CardPanVaultBackfill`) are
  unaffected.
- No new key-management product is introduced; OpenBao Transit is reused infrastructure.

**Negative**
- Adds a DEK lifecycle (wrapped-DEK provisioning, unwrap-at-startup, rewrap-on-rotation) that does
  not exist today — more moving parts than a flat key, and a new failure mode (Transit unreachable
  at boot blocks startup, same fail-fast posture as today's missing-key case but a new dependency
  to have that failure).
- Requires a re-encrypt batch job (old DEK → current DEK) to actually retire a rotated-out DEK;
  until that job runs, the service must retain and trust the old DEK's wrapped form.
- Operational cost: someone has to own the OpenBao Transit key's ACL policy and the rotation
  cadence (cryptoperiod) as a recurring responsibility, not a one-time setup.

**Neutral**
- The CVV-is-stored-at-all question (PCI DSS forbids storing CVV2 after authorization, even
  encrypted, for real cardholder data) is out of scope for this ADR — it is a data-model question,
  not a key-management one, and applies identically before and after this change. Tracked
  separately.

## Future work — multi-cluster / multi-region

Not built now; recorded here so the direction is decided once rather than re-litigated per
deployment. Two facts about *today's* infrastructure bound this:

- OpenBao runs as a **single replica** (`openbank-infra/gitops/apps/openbao.yaml`) — no raft HA
  within the one cluster it already serves. This is buildable today with OSS OpenBao alone (raft
  integrated storage, 3+ replicas across the cluster's AZs, no license/feature gate involved) and
  should happen before any of the below — it closes the more immediate single-point-of-failure gap
  regardless of whether the platform ever goes multi-region.
- The platform runs in one AWS region (`eu-north-1`), one cluster, no multi-region or multi-cloud
  topology exists anywhere in `openbank-infra` today. The questions below are about a future state,
  not a gap in the current one.

**If the platform ever does go multi-region or multi-cloud, the recommended shape is per-region
OpenBao, not a shared/replicated one:**

- Each region (or cloud) runs its own OpenBao cluster (itself HA via raft, per the bullet above)
  with its own `transit/keys/card-pan` KEK. `card-issuance-service` in a given region only ever
  talks to that region's OpenBao — zero cross-region Vault traffic, so no WAN-latency-sensitive
  raft quorum and no dependency on a cross-cluster replication feature.
- Consequence, not a cost: a DEK wrapped by region A's KEK cannot be unwrapped in region B. For a
  bank this is usually the *desired* property anyway — card data typically carries data-residency
  constraints, so per-region PAN vaults (and per-region-issued cards) is the standard shape, not an
  extra one imposed by this design.
- Rotation and the re-encrypt batch job (see Negative, above) run independently per region; nothing
  about them needs to coordinate across regions.

**Rejected direction: a single OpenBao (or Vault/OpenBao replication) stretched across
regions/clouds.** Raft consensus is latency-sensitive — stretching quorum across cloud regions adds
WAN round-trips to every write and risks losing quorum on a region partition, for the exact control
plane that gates every card-issuance pod's boot. Cross-cluster replication has historically been an
Enterprise-only Vault feature; whether OpenBao's OSS line ships an equivalent has not been verified
here and must not be assumed — this ADR does not depend on it either way, because the per-region
shape above needs no replication feature at all. Only reconsider this path if a genuine requirement
for one shared KEK across regions appears (e.g. true active-active failover of the same workload),
which the platform does not have today.

## Compliance impact

- PCI DSS: closes the cryptoperiod gap under Req. 3.6.4 (data-encrypting keys must have a defined
  rotation) and the key-custody gap under Req. 3.5/3.6 (key-management operations behind a
  boundary the application does not itself control), for the day this data stops being synthetic.
  Does not address Req. 3.2 (CVV2 must not be stored after authorization) — see Consequences,
  Neutral.
- DORA: not applicable — no ICT third-party dependency introduced (OpenBao is already
  platform-internal infrastructure).
- GDPR: not applicable — this ADR concerns cryptographic key management for a payment card
  credential, not personal-data processing; the card-issuance service's existing GDPR posture for
  cardholder PII (Art. 32) is unchanged by this decision.
- PSD2: not applicable — no change to strong customer authentication or payment initiation flows.
- CNB: not applicable — no change to reporting or regulatory-filing obligations.

## References

- `openbank-card-issuance-service/src/main/kotlin/com/openbank/cardissuance/infrastructure/crypto/AesGcmCardSecretCipher.kt`
- `openbank-card-issuance-service/src/main/kotlin/com/openbank/cardissuance/application/port/out/CardSecretCipher.kt`
- `openbank-card-issuance-service/src/main/resources/db/migration/V6__card_synthetic_pan_vault.sql`
- `openbank-card-issuance-service/src/main/kotlin/com/openbank/cardissuance/application/usecase/CardPanVaultBackfill.kt`
- `docs/threat-models/openbank-card-issuance-service.md`
