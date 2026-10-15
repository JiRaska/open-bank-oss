---
date: 2026-10-15
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [audit, security, compliance]
summary: "Every hourly audit anchor is also submitted to the public Sigstore Rekor transparency log; the persisted inclusion proof gives third parties external time and completeness evidence while the KMS signature stays the authority."
---

# ADR-0341 — External transparency anchoring for audit anchors: Sigstore Rekor for public inclusion

## Context

The audit anchor (ADR-0031 D5, migration V6) is a signed checkpoint over the hash-chain
head: hourly, KMS `ECC_NIST_P256`, the immutable key id returned by `Sign` recorded on the
row. A third party with the public key can verify — offline, with no bank credentials —
that an anchor row is exactly what was signed, under a key the workload holds only
`Sign`/`Verify`/`GetPublicKey` for (runbook 0014). That is signature and key identity,
and it is real.

It is also all the anchor proves. Runbook 0014 §6 names the two things it cannot:

1. **Completeness** — "absence of an anchor cannot be rejected." The verifier checks the
   anchors it is given. A producer that never captured an anchor for a period, or dropped
   a range before exporting, presents nothing to reject. Today the only backstop is the
   reader manually cross-checking the capture cadence against the producer's own
   `audit-anchor-capture` liveness gauge — the component under test grading its own
   homework, the #2239 lesson in its purest form.
2. **Time** — "`signedAt` is the producer's own claim. There is no external time source in
   the loop, so an anchor cannot prove *when* it was signed, only *what* it attests. A
   backdated history signed with a live key still verifies."

ADR-0031 records the closure of both as ⬜ Planned ("an external RFC 3161 timestamping
authority or a public transparency log — neither exists in this platform today"), and
issue #12923 gap #3 makes the new ADR the fix. ADR-0133 deferred external anchoring of
the audit chain ("External WORM store is the mitigation; deferred"); the time-and-
completeness slot inside that deferral is what this ADR fills — ADR-0133's
deferred-decision slot is **closed** by this document. (The WORM-storage question
itself — archival custody of the log — remains a separate, still-open slot; see
Non-goals.)

The stakes are regulatory, not cosmetic. DORA Art. 17 requires learning from and
reconstructing ICT-related incidents; reconstruction of "when did the bank know, and can
the bank prove it knew then" rests on timestamps a defendant controls. An auditor
receiving an anchor export today must take the bank's word for both coverage and time.

## Decision

**Every anchor is additionally submitted to the public Sigstore Rekor transparency log,
and the persisted inclusion proof becomes part of the anchor's verification surface. The
KMS signature remains the sole authority over *what* was attested; Rekor adds the two
things KMS cannot give: externally witnessed *time* and publicly checkable
*completeness*.**

Concretely:

- **Submission.** After `AuditAnchorService.captureAnchor()` stores the anchor, the
  service submits the `anchorDigest` (SHA-256 hex — 32 bytes, no personal data; the
  attested fields are a chain-head UUID, a hash, a count, a status, and a timestamp) to
  Rekor. No authentication and no bank credentials are involved: Rekor accepts signed
  entries from anyone, which is precisely the property that makes the evidence
  third-party verifiable without trusting the submitter's identity.
- **Persistence.** The Rekor response — log entry UUID, the signed entry (which carries
  Rekor's witnessed `integratedTime`), and the inclusion proof — is stored alongside the
  anchor (a V7 migration adding nullable `rekor_*` columns to `audit_anchor`, same
  immutability rules). The row remains authoritative locally *without* its Rekor fields;
  they are additive evidence.
- **Verification.** `verify-audit-anchors.py` gains a Rekor check per anchor: the
  persisted entry's digest matches the anchor's `anchorDigest`; the signed entry
  timestamp verifies under Rekor's public key (offline, against the archived Rekor
  verification material); and `integratedTime` is within a documented tolerance of
  `signedAt`. Completeness becomes checkable in both directions: an anchor without a
  persisted inclusion proof inside the tolerance window is a *finding*, and the hourly
  cadence means an auditor can enumerate the expected anchor hours in an attested
  window and demand one per hour — absence is now rejectable, not just suspicious.
- **Failure policy.** External submission is **additive evidence, never a gate on local
  capture.** Anchors are never withheld because Rekor is unreachable: capture stays
  authoritative and uninterrupted; failed submissions land in a retry queue and page via
  a fail-loud alert on backlog age (its own metric, not folded into the producer's
  liveness gauge). A missing inclusion proof degrades the *attestation verdict*, never
  the *log*.

RFC 3161 timestamping (a qualified TSA) is a **documented future option**, not part of
this decision: it adds a contractual counterparty, credentials to custody, and per-hour
cost for a strictly stronger time claim, and is the right move if a supervisor or
eIDAS-aligned assessment ever demands qualified-time lineage. Rekor's witnessed time
bounds backdating to the tolerance window today.

## Alternatives considered

- **RFC 3161 TSA tokens per anchor** — a qualified timestamp authority signs
  `anchorDigest ‖ time`, giving standards-traceable qualified time (eIDAS timestamping
  lineage as a non-normative reference). Third parties verify with the TSA's public
  cert chain — no bank credentials needed, and the strongest *time* story of the
  options. Rejected as the default: it needs a QTSP contract and credentials per
  environment, adds a paid per-anchor call at hourly cadence, and solves *time* only —
  completeness stays procedural. A TSA outage directly threatens evidence production
  unless the same retry/queue machinery is built anyway. Kept as the documented future
  option above.
- **Public transparency log — Sigstore Rekor (CHOSEN)** — the fleet already runs Sigstore
  tooling for release evidence (ADR-0029's cosign/SLSA bundle), so the ecosystem,
  verification libraries, and operational vocabulary exist in-house. Submission is
  unauthenticated and free, so there is no new credential to custody and no contractual
  counterparty; verification needs only Rekor's public verification material, fully
  third-party. Failure modes are tame: Rekor has had no material prolonged outage, and a
  degraded window is covered by the retry queue with fail-loud alerting — local capture
  is untouched. The residual risk is Rekor-the-service's long-term fate: mitigated
  because the persisted inclusion proof plus archived Rekor public keys keep every
  already-submitted anchor verifiable offline forever; a future TSA or second log can
  re-anchor the window (option 3) if Rekor ever sunsets. Regulator acceptability: the
  evidence answers DORA Art. 17's reconstruction questions with public, non-bank-hosted
  time and completeness witnesses.
- **Both TSA (time) and Rekor (inclusion)** — the belt-and-braces answer and the right
  end-state if a ČNB audit ever demands qualified-time lineage. Rejected *for now*:
  it pays the full TSA cost (contract, credentials, per-hour calls) before any
  regulator has asked for it, and doubles the submission-failure machinery to build and
  alert on. The decision is deliberately structured so this is an additive upgrade, not
  a redesign.
- **Status quo + procedural controls** — strengthen the runbook's cadence cross-check
  and call it completeness; keep `signedAt` and call it time. Rejected as the default:
  the procedure still grades the producer by the producer's own gauge (the exact #2239
  shape runbook 0014 §6.1 already warns about), and "we checked our own clock" is not a
  time claim a supervisor can accept. Cheaper by one migration, worth nothing under the
  threat model. It remains the honest description of what ships *until* this ADR's
  delivery lands — the D5 status must not advance in the meantime.

## Consequences

**Positive**

- Absence of an anchor for a period becomes machine-detectable: the attestation
  workflow and the offline verifier can each enumerate expected hourly anchors in the
  attested window and reject gaps, closing runbook 0014 §6.1.
- `signedAt` stops being a bare producer claim: Rekor's witnessed `integratedTime` bounds
  any backdating to the documented tolerance window, closing §6.2 for KMS-era anchors.
- Third-party verification needs no bank credentials *and no bank availability*: the
  anchor export plus archived Rekor verification material suffice, strengthening the
  runbook 0014 §3 trust table.
- The weekly live-attestation workflow's `verdict.json` gains a completeness dimension
  (expected vs present anchors in window, inclusion-proof coverage, worst-of verdict
  semantics preserved) — the artifact D5's release evidence consumes stops carrying
  `not_claimed` for time and completeness.

**Negative**

- A V7 migration and `AuditAnchorService` change (submission call, retry queue, Rekor
  metric), plus verifier, attestation-workflow, and runbook 0014 §6 rewrites — the full
  fleet-fit cost of one more hourly outbound dependency.
- Rekor submission leaks nothing sensitive (the digest is a hash over non-personal
  checkpoint fields) but it is a public, permanent write; that is the point of a
  transparency log and must be stated in the runbook so nobody "fixes" it.
- Rekor downtime or sunset creates an attestation-degradation window; the retry queue
  bounds it, and archived verification material bounds the blast radius of a sunset,
  but the operational runbook must carry both explicitly.

**Neutral**

- The pre-KMS HMAC window (issue #12923 gap #8) is untouched by this ADR; re-anchoring
  or bounding that window is decided there, not here.
- `--entries` remains optional in the verifier; this ADR does not make entry-level
  verification part of the default claim.
- The submission path uses no authentication by design; if abuse of the endpoint ever
  became a problem, batch submission and rate-limit handling live in the retry queue,
  not in this decision.

## Non-goals

- **External WORM storage** of the audit log itself (AWS QLDB / Immudb / S3 Object
  Lock). ADR-0133 deferred it; this ADR closes only the time-and-completeness slot.
  WORM custody remains a separate, still-open decision — Rekor inclusion proofs are
  small checkpoints, not an archival store.
- **Entries-level verification by default** — the `--entries` flag stays optional;
  anchor-surface completeness is the claim this ADR adds.

## Compliance impact

- DORA: Art. 17 (learning from and reconstructing ICT-related incidents) — external
  time and completeness evidence directly serves reconstruction; Art. 9(4)(d) integrity
  posture is strengthened by detectable gaps.
- GDPR: Art. 32 (integrity) — additive; no new personal data is submitted to the public
  log (the digest hashes non-personal checkpoint fields).
- ČNB: AML Decree Section 9 record integrity — external anchoring supports the
  evidentiary standing of the audit trail; a qualified-TSA upgrade would align with
  eIDAS timestamping lineage (non-normative reference, not claimed as compliance).
- PCI DSS: Req. 10.3 (audit log protection) — reinforced coverage claim.
- PSD2: not directly applicable.

## References

- ADR-0133 — Tamper-evident audit chain (the deferred external-anchoring slot this ADR
  closes for time and completeness)
- ADR-0031 — AI agent governance and operations (D5; records RFC 3161 / transparency
  log closure as ⬜ Planned)
- ADR-0172 — Cryptographic key management and lifecycle (KMS key custody the anchors
  already depend on)
- ADR-0029 — Versioning, release and governance as code (Sigstore/cosign precedent in
  the release pipeline)
- Runbook 0014 — Independent audit anchor verification, §6 (the three structural gaps)
- `openbank-audit-service` — `V6__audit_anchor.sql`, `AuditAnchor.kt`,
  `AuditAnchorService.kt`, `AwsKmsAnchorSigner.kt`
- `.github/scripts/verify-audit-anchors.py` — offline verifier to be extended with the
  inclusion-proof check
- `.github/workflows/audit-anchor-attestation.yml` — weekly live attestation whose
  `verdict.json` gains the completeness dimension
- Issue #12923 — Phase 5 (D5) gap analysis, gap #3
