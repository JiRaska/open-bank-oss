---
date: 2026-07-16
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [privacy-gdpr, compliance, infrastructure]
summary: "The estate's residency is fixed at eu-north-1 and decided here rather than in a README; document-service's eu-central-1 default is a defect to fix, and the un-enforced LLM prompt egress to a US provider is stated as an open exposure."
---

# ADR-0175 — Data residency and sovereignty

## Context

An EU bank must be able to say where its data is. This platform cannot, and the pieces that ought to
say it disagree with each other.

[ADR-0027](0027-cloud-agnostic-in-cluster-substrate.md) D4 says "**EU residency**" and stops there — no
region, no classes, no rule about what may cross a boundary. [ADR-0152](0152-single-tenancy-boundary-statement.md)
twice leans on residency ("simplifies data-residency and GDPR controllership reasoning: one deployment,
one controller, one regulator relationship") without making a residency commitment. The platform audit
(`docs/audits/2026-07-16-platform-audit.md` §3.3) ranked this the #4 missing ADR domain.

Three concrete contradictions made this ADR urgent rather than tidy:

1. **`openbank-infra/aws/README.md` states "Production is pinned to `eu-central-1` per ADR-0027's GDPR
   condition". ADR-0027 contains no such condition** — its five go-live conditions are audit, state,
   backups, secrets, network, and D4 says only "EU residency". A regional pin is attributed to an ADR
   that does not make it. (ADR-0027 is itself a 2026-06-14 reconstruction of a lost original; the pin
   may have been in the original, which is precisely why it needs re-deciding rather than inferring.)
2. **`openbank-document-service` defaults to a different region than the entire estate:**
   `region: ${OBJECTSTORE_S3_REGION:eu-central-1}` while everything else is `eu-north-1`. Customer
   documents (ADR-0161) are the one workload whose residency is most likely to be asked about.
3. **Prompt data leaves the EU today.** The copilot is customer-facing (ADR-0089), enabled in sandbox,
   and calls `api.deepinfra.com` — a bare global endpoint, US-headquartered, no region pinning, no DPA,
   no zero-retention agreement. The only control is a YAML comment saying "synthetic data only".

## Decision

**1. The estate is `eu-north-1` (Stockholm), AWS account `265175468565`.** Uniform across bootstrap,
sandbox-platform and web-prod; VPC spans three AZs. All five S3 buckets, KMS, ECR, CloudTrail and
CloudWatch inherit it. **This is the residency statement that did not exist.**

**2. Region is decided here, not in a README.** The `eu-central-1` production pin asserted by
`openbank-infra/aws/README.md` is **not** an ADR-0027 condition and is hereby **not adopted by
inference**. If production is to run in `eu-central-1`, that is a decision this ADR (or its successor)
makes explicitly. Until then: **eu-north-1 is the estate, and the README is wrong** — D1 corrects it.

**3. `openbank-document-service`'s `eu-central-1` default is a defect, not an intent.** It is the only
component defaulting outside the estate region, and it holds customer documents. Either the estate is
multi-region (it is not) or this is a latent misconfiguration. **D2** aligns it. Treating it as an
unstated intent would be exactly the kind of inference §2 rejects.

**4. Personal data does not leave the EU. Today, we cannot prove that — so we state the exposure.**
The copilot and the ops agents send prompt content to DeepInfra (US, global endpoint, no residency
pinning). The stated basis is "synthetic data only", asserted in YAML comments across copilot and
devops-agent, with **no technical control** preventing real data reaching it and no DPA behind it.

The `model-gateway` config shape already carries a per-model **`sensitivity: hosted | self-hosted`**
field — the mechanism for residency routing exists. What does not exist is anything that enforces it:
ADR-0031's `sensitive_data → self_hosted` routing, the vLLM tier, and the LiteLLM gateway are all
unbuilt ([ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) §2), while ~16 OPA bundles
embed policy data asserting that routing **is** in force.

**So the honest position is:** for a sandbox on synthetic data, hosted-LLM egress is acceptable. **It
is not acceptable for any deployment holding real personal data**, and the control that would make it
acceptable does not exist. D3 is the gate that makes this a rule rather than a comment.

**5. Residency classes.** Three, and only the third may leave the EU:
- **Regulated & personal** — ledger, accounts, parties, KYC, documents, audit, backups. `eu-north-1`,
  never leaves. No exceptions, no "just for a moment" in a hosted model's context window.
- **Operational** — metrics, traces, logs. In-cluster (Prometheus/Tempo/Loki), same region.
- **Synthetic / non-personal** — demo data, seeded fixtures, ops-agent prompts about *infrastructure*
  (not customers). May reach a hosted model. **This is the only class §4's egress is licensed for.**

**6. Backups inherit the estate region, and the sandbox posture is not the prod posture.**
`openbank-sandbox-db-backups` is `eu-north-1`, SSE-`AES256` (not KMS), `force_destroy = true`, 35-day
lifecycle. The WORM `log_archive` is Object Lock COMPLIANCE with `log_retention_days` defaulting to
**1** so `tofu destroy` is not permanently blocked. Both carry comments saying production must differ
(10y retention, compliance-grade lock). Those comments are correct and this ADR keeps them visible
rather than letting them read as the target state.

## Decisions to deliver

- **D1 — Correct `openbank-infra/aws/README.md`.** It attributes a regional pin to a condition
  ADR-0027 does not contain. A wrong provenance claim in the infra README is worse than no claim: it
  looks authoritative. *(Pending)*
- **D2 — Align `openbank-document-service` to `eu-north-1`** (or state why customer documents live in a
  second region). *(Pending)*
- **D3 — Gate hosted-LLM egress on the data class.** Either enforce `sensitivity: self-hosted` routing
  for anything non-synthetic (ADR-0031's decision, unbuilt), or hard-disable the customer-facing
  copilot outside sandbox. The current control is a comment. **OPEN — highest-value item here.
  All inference currently routes to hosted providers (Groq/DeepInfra); no sensitive-data routing is
  enforced. Tracked in issue #3599 (LiteLLM choke-point work for data-residency enforcement). Not
  acceptable for any deployment holding real personal data.** *(Pending)*
- **D4 — Correct the OPA bundles' residency claim.** ~16 bundles assert `routing: {sensitive_data:
  self_hosted}` as live policy data. Shared with [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md)
  D1 — one fix, two ADRs. *(Pending)*
- **D5 — A cross-border transfer analysis** — GDPR Chapter V, SCCs, a TIA for the LLM provider. None
  exists. Required before any real personal data exists, not before. *(Pending)*
- **D6 — Assert bucket regions explicitly.** Every S3 bucket inherits the provider default; none
  declares a region or a residency guard. It happens to be right, which is not the same as being
  constrained. *(Pending)*

## Alternatives considered

- **Pin production to `eu-central-1` (Frankfurt), as the README claims.** The conventional choice for
  an EU bank, and the README may be echoing the lost ADR-0027 original. Rejected *by inference*: the
  entire estate, account, and every IaC default is `eu-north-1`, and adopting a different prod region
  because a README says so would be exactly the unsourced-claim problem this ADR exists to fix. If
  Frankfurt is wanted, it is a decision to make with reasons — data-subject proximity, latency,
  sovereignty posture — not one to inherit from a sentence.
- **Self-host the LLM (vLLM) now and close the egress question.** The clean answer, and already
  ADR-0031's decision. Rejected on cost: GPU capacity for a demo estate is not justifiable when
  ADR-0027's whole posture is Graviton-spot FinOps. D3 buys the same protection by *classifying* the
  data instead of relocating the model.
- **Say "EU residency" and move on, as ADR-0027 did.** Rejected: that is what produced this mess. "EU
  residency" without a region, classes, or an egress rule is a slogan — it did not stop a
  customer-facing assistant calling a US endpoint, and it did not stop a second region appearing in a
  service default.
- **Ban hosted LLMs outright.** Simple and enforceable. Rejected: it would kill the ops agents (which
  reason about infrastructure, not customers — genuinely class 3) to protect data they never see. §5's
  classes are the proportionate line.

## Consequences

**Positive**
- The platform can now answer "where is the data?" — `eu-north-1` — with a sourced statement rather
  than three artifacts that disagree.
- The README/ADR-0027 contradiction is resolved by deciding, not by picking whichever text was found
  first.
- §4 converts an implicit "we assume it's fine because the comment says synthetic" into a stated
  exposure with a gate (D3) attached.

**Negative**
- This ADR states in public that a customer-facing assistant currently sends prompts to a US provider
  with no DPA, protected only by a comment. That is a genuine disclosure — and it is true, discoverable
  in one grep, and better owned than found.
- Six pending decisions. The residency *position* is now decided; the *controls* are not built.

**Neutral**
- No runtime change. D1–D6 carry the delivery.

## Compliance impact

- **GDPR Art. 44–49 (Chapter V — transfers):** the LLM egress is a third-country transfer with no SCCs,
  no TIA and no DPA. Tolerable only while the data is synthetic (§5 class 3). D5 is the gap; D3 is the
  control that keeps §4's assertion true.
- **GDPR Art. 5(1)(f) / Art. 32:** residency is an integrity-and-confidentiality control. §1 is the
  statement; §5 the classes.
- **GDPR Art. 30 (ROPA):** `docs/strategy/07-compliance-matrix.md` maps Art. 30 to "governance +
  audit-service" — no artifact exists. Out of scope here; named so it is not mistaken for covered.
- **DORA Art. 28–30:** the LLM provider is simultaneously a third-party (ADR-0174) and a transfer
  (this ADR). One dependency, two regimes.
- **ADR-0152 (single tenancy):** its claim that single-tenancy "simplifies data-residency reasoning"
  now has something concrete to simplify *to*.
- **PCI DSS / CNB:** not applicable — no cardholder data; no separate reporting obligation.

## References

- [ADR-0027](0027-cloud-agnostic-in-cluster-substrate.md) — D4 "EU residency"; the phrase this ADR makes operable
- [ADR-0152](0152-single-tenancy-boundary-statement.md) — leans on residency reasoning without stating it
- [ADR-0031](0031-ai-agent-governance-and-operations.md) — the `sensitive_data → self_hosted` routing that is unbuilt
- [ADR-0089](0089-customer-facing-ai-assistant.md) — the customer-facing assistant whose egress §4 covers
- [ADR-0161](0161-object-storage-standard-for-application-documents.md) — customer documents; D2's `eu-central-1` default
- [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) — the same LLM dependency, as a third-party register entry
- [ADR-0118](0118-gdpr-data-lifecycle-and-retention.md) — PII classification and retention; the classes §5 builds on
- `openbank-infra/aws/README.md` — the `eu-central-1` claim D1 corrects
- `docs/audits/2026-07-16-platform-audit.md` §3.3 — the gap this ADR closes
