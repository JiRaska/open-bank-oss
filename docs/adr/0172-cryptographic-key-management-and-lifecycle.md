---
date: 2026-07-16
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [crypto-keys, secrets, compliance]
summary: "This ADR becomes the platform's key inventory of record with custody and rotation per key class, and sets a preference order from no key at all through short-lived leaves to long-lived keys, ending the circular ADR-0007/0094 deferral."
---

> **Delivery update (2026-08-20):** D6 is now shipped as the provider-neutral
> [`0012-cryptographic-key-compromise`](../runbooks/0012-cryptographic-key-compromise.md)
> runbook. It defines durable incident declaration, evidence preservation, fail-closed
> containment, replacement verification, and closure artifacts. It intentionally does
> not claim that a live KMS rotation or tabletop has been executed; those remain
> operational acceptance evidence for D1/D6.

# ADR-0172 — Cryptographic key management and lifecycle

## Context

The platform has an unusually complete **secrets** story — [ADR-0007](0007-vault-for-secrets-management.md)
(OpenBao) and [ADR-0099](0099-automated-secret-rotation.md) (dynamic credentials + a weekly rotator) —
and no story at all for **cryptographic keys**. Those are different things. A secret is a password you
can change; a key is an identity or a trust anchor, and rotating one does not undo what it signed.

The platform audit (`docs/audits/2026-07-16-platform-audit.md` §3.3) ranked this the #2 missing ADR
domain. The only prior statement is one deferred bullet in ADR-0007 — and that deferral is
**circular**: ADR-0007 defers the HSM until eIDAS activates (ADR-0094), while ADR-0094 defers back to
"KMS/HSM-backed". Two ADRs each point at the other, so nobody owns it.

This ADR is the inventory, the custody model, and — where there is a gap — a plainly-stated gap rather
than a plan we have not funded. It deliberately does **not** re-decide secret custody (ADR-0007) or
secret rotation (ADR-0099).

## Decision

**1. This ADR is the key inventory of record.** A key not listed here is not governed.

| Class | Key | Custody | Rotation |
|---|---|---|---|
| **Code signing** | `alias/openbank-cosign-signing` (ECC_NIST_P256) — images + SBOM attestations | AWS KMS, eu-north-1 | **Manual, undefined** |
| **Unseal** | `alias/openbank-vault-unseal` — OpenBao auto-unseal | AWS KMS | **None** |
| **Envelope** | `aws_kms_key.secrets` (EKS etcd), `aws_kms_key.audit` (S3/CloudTrail) | AWS KMS, OpenTofu | Annual, automatic |
| **DNSSEC** | `aws_kms_key.dnssec` (Route53 KSK) | AWS KMS, OpenTofu | **Disabled** (`enable_key_rotation = false`) |
| **Transport CA** | Strimzi cluster CA + clients CA, ~33 KafkaUser certs | Strimzi operator, `messaging` ns | Strimzi defaults — **not configured, not documented** |
| **Document signing** | `pki-document-signing` root (RSA-2048, 10y) → per-ceremony leaf (300s, `no_store`) | OpenBao PKI, in-cluster | Root: **none**. Leaf: ephemeral |
| **Institutional seal** | the bank's own long-lived PAdES-sealing PKCS12 — `openbank/document-service-signing-seal` (`KEYSTORE_P12_BASE64` + `KEYSTORE_PASSWORD`). Distinct from the row above: that mints the *client's* one-time cert, this is the *bank's* identity on every sealed document | OpenBao KV, seeded out-of-band by an operator (runbook 0008) | **None** — and **not yet seeded**: `PdfBoxPadesSealAdapter` falls back to a DEV-ONLY ephemeral cert (#1284) |
| **Agent identity** | `pki-agent` CA (ADR-0031 D3b) | OpenBao PKI | Not documented |
| **Credential issuance** | EUDI issuer EC P-256 private JWK — signs every PID/(Q)EAA | OpenBao KV | **None** |
| **PII index** | RČ pepper (HMAC-SHA256, `BlindIndex`) | OpenBao KV | Manual + re-index (`index_key_version`) |
| **Session** | JWT signing keys, OIDC client secrets | OpenBao KV | **Weekly**, automated (ADR-0099) |
| **Erasure** | Transit per-subject keys (GDPR Art. 17 crypto-shredding) | OpenBao Transit | Destroyed on erasure |
| **Public TLS** | Let's Encrypt certs | cert-manager, ACME DNS-01 | Automatic |
| **Provenance** | Sigstore keyless (GitHub OIDC → Rekor) | Ephemeral | N/A — no key to rotate |
| **Commit** | GPG/SSH signatures | Developer-held | Developer |

**2. Prefer no key at all, then a short-lived one.** Order of preference for anything new: Sigstore
keyless (no key) → per-act leaf cert (300s) → dynamic credential (24h) → automated weekly rotation →
long-lived key with a documented ceremony. A new long-lived key must justify why it cannot sit higher.

**3. The two crown-jewel keys are NOT in OpenTofu, and that is recorded here as a defect, not a
design.** `alias/openbank-cosign-signing` and `alias/openbank-vault-unseal` are *referenced* by
workflows and manifests but *declared* nowhere — only three `aws_kms_key` resources exist in the repo
(audit, eks-secrets, dnssec). They were created out-of-band, so they have no reviewed key policy, no
rotation setting, no tags, and no drift detection. `aws/envs/sandbox-platform/arc-runners.tf` compounds
it by granting `kms:Sign` against a **hardcoded key UUID** with no matching resource. D1 fixes this;
until then, this table is the only artifact that says these keys exist.

**4. Signing keys and encryption keys get different custody rules — because their compromise differs
in kind.** A compromised encryption key exposes data from that point on; a compromised **signing** key
retroactively forges everything it ever vouched for, and rotation does not undo that. Therefore:
- Code-signing and credential-issuance keys are **KMS-resident and never exported**; the platform never
  holds their private material.
- Verification uses public material only (Kyverno holds a PEM literal; the private key never leaves KMS).
- There is **no key ceremony, no dual control, and no split knowledge** for any signing key today. For
  a reference implementation that is an accepted gap; for a licensed bank it would not be.

**5. HSM: deferred behind a named condition, not behind another ADR.** No HSM or FIPS 140-3 boundary
exists. Signing therefore claims **advanced**, never **qualified**, eIDAS assurance — and the code says
so (`SignatureCeremony.kt`). This ADR breaks the ADR-0007 ↔ ADR-0094 circular deferral by owning the
condition outright: **an HSM is required before the first QES/QSeal signature is issued to a real
party, and not before.** Until then the honest claim is "advanced signature, software key", which is
what we make.

**6. The document-signing root is not a trust anchor we would defend.** A self-generated in-cluster
RSA-2048 root, 10-year TTL, generate-once guard, no offline material, no hierarchy, no CRL/OCSP. Fine
for demonstrating the *flow*; not something eIDAS would accept. Recorded now rather than discovered
later.

## Decisions to deliver

- **D1 — Bring the cosign and unseal keys under OpenTofu.** `aws_kms_key` + `aws_kms_alias` + reviewed
  key policy for both; replace the hardcoded `kms:Sign` UUID with a resource reference. Unlocks
  rotation config, tags, drift detection. *(Pending)*
- **D2 — Fail closed on the PAdES seal.** `PdfBoxPadesSealAdapter` falls back to an ephemeral
  self-signed cert when the keystore path is absent **or the file merely does not exist**, logging only
  a `WARN`; the guard is opt-in via `OPENBANK_SIGNATURE_REQUIRE_TRUSTED_ISSUER=true`. A misconfigured
  mount therefore produces legally worthless signatures that look successful. Make fail-closed the
  default outside `%dev`. *(Pending — highest value here.)*
- **D3 — Document the Kafka CA lifetimes.** `kafka.yaml` has no `clusterCa`/`clientsCa` block, so
  Strimzi defaults silently apply. State the validity/renewal figures, or set them. *(Pending)*
- **D4 — DNSSEC KSK rotation.** `enable_key_rotation = false` with no compensating manual procedure.
  Enable it or write the runbook. *(Pending)*
- **D5 — Separate the EUDI issuer key from the RČ pepper.** Both sit in the same OpenBao KV path. A
  credential-issuing key co-located with a PII pepper fails separation of duties. *(Pending)*
- **D6 — A key-compromise runbook.** `docs/runbooks/` covers OpenBao operations (0002/0005/0006/0007/
  0008) but nothing covers "the signing key is compromised" — the one case rotation cannot fix.
  *(Pending)*

## Alternatives considered

- **Fold this into ADR-0007.** Fewest documents. Rejected: ADR-0007 is about *secret custody* and is
  already `Partial` on four axes. Keys have a different lifecycle and a different failure mode
  (retroactive forgery vs forward exposure) — and burying the inventory in a secrets ADR is precisely
  how it stayed invisible this long.
- **Adopt an HSM now.** The auditor-pleasing answer. Rejected: no QES is issued to any real party, the
  platform is explicitly a reference implementation, and an unused HSM is cost and operational surface
  bought to satisfy a document. §5 states the honest trigger instead.
- **Rotate the cosign key on a schedule.** Rejected as premature: rotation without a revocation story
  for already-signed images is theatre, and you cannot rotate what OpenTofu does not know exists. D1
  first.
- **Go fully Sigstore-keyless and drop the KMS signing key.** Genuinely attractive — the release
  pipeline already uses keyless for SLSA provenance. Rejected for now: Kyverno verifies against a PEM
  **offline** at admission, and keyless verification needs Rekor reachable from the cluster — a new
  external runtime dependency on the admission path for every pod
  ([ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md)). Revisit if that calculus
  changes.

## Consequences

**Positive**
- One place now answers "what keys exist, who holds them, when do they change" — the first question an
  auditor asks, and one the platform could not previously answer.
- The circular ADR-0007 ↔ ADR-0094 HSM deferral is closed: §5 names the trigger.
- D2 converts a silent legal failure (worthless signatures, `WARN` only) into a startup failure.

**Negative**
- The inventory is a disclosure document: it tells a reader exactly which keys are unrotated and which
  are not in IaC. That is the right trade for a public reference implementation whose value is its
  honesty — but it is a real trade, and worth making deliberately.
- Six pending decisions is a backlog, not a fix. This ADR makes the gaps trackable; it does not close
  them.

**Neutral**
- No runtime change. This is an inventory plus decisions; D1–D6 carry the delivery.

## Compliance impact

- **DORA:** cryptographic key management is an explicit Art. 9 protection control. This ADR is the
  artifact; D1/D3/D4/D6 are the gaps. Note `docs/bcp/dora-ictrm.md` currently maps Art. 9 to access
  control / encryption / patching and does not mention key lifecycle — this ADR fills that.
- **eIDAS / eIDAS2:** signatures are **advanced**, never qualified — no HSM, no QSCD. §5 is the
  condition under which that changes. ADR-0094 / ADR-0162 / ADR-0170 all defer QES to phase 2; this
  ADR now owns why.
- **GDPR:** the RČ pepper (`BlindIndex`) and the Transit crypto-shredding keys are personal-data
  controls. Rotating the pepper requires a re-index migration (`index_key_version`), which is why it is
  manual. Note Transit is wired in the **local-dev bootstrap only** and `VaultCryptoErasure` is
  build-property gated off, so crypto-shredding does not run in-cluster today (ADR-0023 owns that).
- **PCI DSS:** not applicable — no cardholder data is encrypted by these keys.
- **CNB:** no separate obligation.

## References

- [ADR-0007](0007-vault-for-secrets-management.md) — secret custody (OpenBao); source of the HSM deferral this ADR takes over
- [ADR-0099](0099-automated-secret-rotation.md) — secret *rotation*: dynamic credentials + the weekly rotator
- [ADR-0027](0027-cloud-agnostic-in-cluster-substrate.md) — why OpenBao rather than HashiCorp Vault
- [ADR-0030](0030-supply-chain-security-and-ssdlc-hardening.md) — what the cosign key signs; the admission policy that verifies it
- [ADR-0031](0031-ai-agent-governance-and-operations.md) — D3b, the `pki-agent` identity CA
- [ADR-0094](0094-eudi-native-identity-hub.md) — the EUDI issuer key; the other half of the circular HSM deferral
- [ADR-0137](0137-kafka-mtls-scheme-accepted-migration.md) — Kafka mTLS and the KafkaUser certs
- [ADR-0162](0162-document-management-templating-and-e-signature-architecture.md) — PAdES sealing; the fail-open bug D2 closes
- `docs/audits/2026-07-16-platform-audit.md` §3.3 — the gap this ADR closes
