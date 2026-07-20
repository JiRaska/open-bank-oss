---
date: 2026-05-30
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [supply-chain, security-ops, testing, governance]
summary: "Supply-chain security gains a tracked vulnerability lifecycle with VEX and SLAs, mandatory threat models for money-path services, mutation and DAST testing, signature-verifying admission control and SBOM drift detection."
---

# 30. Supply-chain security and SSDLC hardening: VEX, vulnerability lifecycle, threat modeling, admission policy

**Delivery note (updated 2026-07-11):**
- **D2 (threat-model CI gate)** — ✅ Shipped, corrected 2026-07-11: `check-threat-models.py` runs as a required check in `Validate manifests`; all money-path services have STRIDE/DFD threat models at `docs/threat-models/<service>.md`. The diff-aware rule this note previously listed as pending **already shipped** (PR #389, `openbank-infra/scripts/check-threat-model-diff.py`, wired into `ci.yml`): a changed inbound/outbound trust boundary for a money-path service (new REST/client code, `application.yaml` auth/transport keys, or its gitops NetworkPolicy/Deployment) without a matching `docs/threat-models/<service>.md` change in the same diff is flagged — advisory today, per ADR-0144 gate graduation. The gate's own parser is now unit-tested (`check_threat_model_diff_test.py`) and the previously-unrun `openbank-infra/scripts/*_test.py` suites execute in CI.
- **D4 (Kyverno admission control)** — ✅ Shipped, further along than the previous note said: trust root chosen (AWS KMS key `alias/openbank-cosign-signing`), `build-push-{service,admin-ui}.sh` sign every pushed image, and `verify-images-policy.yaml`'s image-signature rule is **Enforce** fleet-wide (verified while auditing ADR delivery status, 2026-07-05 — see `openbank-infra/gitops/components/kyverno/verify-images-policy.yaml`). What remains: a **second** `verifyImages` rule requiring SBOM attestation specifically (not just the signature) at admission — still "(planned)" in that same policy file (tracked in ADR-0121, whose Axis 2 covers this).
- **D1 (VEX + vulnerability-management lifecycle)** — ✅ Shipped (2026-07-06, PR #315 + PR #325): `build-release-evidence.sh` emits an OpenVEX 0.2.0 document (`<tag>.vex.json`, cosign-signed) per release, merging Trivy's `under_investigation` inventory with human-triaged verdicts from `openbank-libs/governance/vex/<component>.openvex.json`. `image-rescan.yml` (weekly) rescans every published GHCR image and passes each component's VEX overlay to `trivy image --vex` so triaged findings don't re-alarm. `vex-triage.yml` (weekly) closes the loop: `vex-inventory.py` aggregates the fleet's `under_investigation` queue, `vex-triage.py` opens one SLA-tracked GitHub issue per CVE (severity from a real CVSS v3.1 base-score computation off api.osv.dev, `rules.yaml: vuln_management.sla_days` is the clock), auto-closes issues whose CVE left the queue, and fails the weekly run (`::error`, non-zero exit) on any SLA breach. `rules.yaml: vuln_management` now carries `gate`/`enforced: active`/`ci_producer` for this lifecycle. Not covered: Trivy's *own* CI gate (`security.yml`) does not yet consume the VEX overlay to suppress already-triaged findings at PR time — only the two scheduled workflows do.
- **D3 (mutation testing + DAST)** — ✅ Shipped, corrected 2026-07-11: this note previously said pitest was "configured for openbank-lending-service domain" — that was never actually true (lending has a 1-file thin domain and was deliberately excluded, see below); the accurate baseline is `pitest.yml`'s weekly-scheduled matrix, which now covers **10** money-path services with substantive domain logic (ledger, balance, account, transaction, fx, sepa-payment, sepa-instant, domestic-payment, fraud, and — added 2026-07-11 — sanctions, 67% local mutation-kill baseline). The remaining 7 (clearing, swift, sca, consent, billing, settlement, lending) are **deliberately excluded** per ADR-0063's thin-domain criterion (1-2 domain files — mutation testing there is noise, not signal), not an oversight. DAST: `dast-zap-baseline.yml` deployed.
- **D5 (runtime SBOM drift detection)** — ⬜ Pending. Genuinely not started — no design exists yet for comparing a running container's actual installed packages against its build-time SBOM (would need an in-cluster periodic job or admission-time check).

Remaining D5 tracked in issue #265.

> **Accepted (2026-06-11).** Status raised from Proposed to reflect established practice:
> this ADR is partially implemented and load-bearing — the money-path threat-model gate
> (`check-threat-models.py`, D2) runs as a required CI check with all money-path services
> covered, and 26+ Accepted ADRs (and the money-path 2-approval rule in `rules.yaml`)
> reference it. Items not yet realized (e.g. the diff-aware threat-model rule, parts of
> D3–D5; see D6 phasing) remain tracked follow-ups; acceptance covers the decision, not a
> claim that every phase has shipped.

## Context

OpenBank has good security **detection** — `security.yml` runs CodeQL (SAST), Trivy (SCA + IaC), Syft +
Gradle CycloneDX (SBOM), gitleaks (secrets); Dependabot opens dependency PRs; Vault holds secrets
(ADR-0017); OPA does fine-grained authz (ADR-0018). ADR-0029 adds the missing *integrity* layer — signed
artifacts, SLSA provenance, signed SBOMs, a closed audit chain.

What remains absent is the **security lifecycle and its enforcement** — the parts of a modern SSDLC
(NIST SSDF SP 800-218, SLSA, OWASP SAMM/DSOMM) that turn detection into managed risk:

- **No vulnerability-management lifecycle.** Scanners emit findings, but there is no defined triage,
  ownership, severity-based SLA, or closure workflow. Detection without a remediation process is noise,
  not control. (NIST SSDF RV.1–RV.3.)
- **No VEX (Vulnerability Exploitability eXchange).** Every Trivy/CodeQL finding is treated as open;
  there is no machine-readable statement of "this CVE is not exploitable in our context, because…". At
  30 services this buries real findings under false positives.
- **No threat modeling.** ADRs capture "Compliance impact" but no structured STRIDE/DFD threat model
  exists, not even for money-path services (ledger, transaction, payment, lending). Design-phase
  security is implicit. (NIST SSDF PW.1.)
- **Shallow test depth for a bank.** Coverage is line-only with a 39% floor (ADR-0020); there is no
  mutation testing to validate the tests actually assert behavior, and no DAST against running services.
- **Provenance is produced but not enforced at the boundary.** ADR-0029 will *sign* images and emit SLSA
  attestations, but nothing at deploy time *verifies* them. An unsigned or unprovenanced image could
  still be admitted. The chain is only as strong as its enforcement point.
- **No runtime SBOM drift detection.** A signed SBOM at release time says nothing about what is actually
  running weeks later as base images and sidecars change.

ADR-0029 *produces* signed provenance and the audit chain; **this ADR enforces and operationalizes the
security side of that chain.** They are deliberately split: 0029 is governance/release (performance,
auditability, multi-agent readiness); 0030 is security lifecycle (risk management, threat modeling,
admission control). Money-path services are the priority everywhere a choice of scope is made.

Status legend: 🟢 GREEN = built + tested; 🟡 YELLOW = scaffolded; ⬜ PLANNED = scoped here.

## Decision

We will operationalize a managed security lifecycle on top of ADR-0029's provenance: a
vulnerability-management workflow with VEX and SLAs, mandatory threat models for money-path services,
deeper testing (mutation + DAST), deploy-time admission control that verifies signatures/provenance, and
runtime SBOM drift detection.

### D1 — Vulnerability-management lifecycle + VEX

- **Findings flow into a tracked lifecycle**, not just SARIF uploads: scanner output (Trivy, CodeQL,
  Dependabot) is triaged with an owner (via CODEOWNERS), a severity, and an SLA — e.g. Critical 7 days,
  High 30, Medium 90 — measured from detection to closure. SLA breaches surface in the admin UI
  governance view alongside coverage (ADR-0029 D3).
- **VEX documents** (CycloneDX VEX or OpenVEX) accompany each release's SBOM (ADR-0029 evidence bundle):
  machine-readable `not_affected` / `affected` / `fixed` statements with justification. Trivy consumes
  the VEX to suppress non-exploitable findings, so the gate fails only on *actionable* vulnerabilities.

### D2 — Threat modeling in the Definition of Done (money-path first)

- A lightweight **STRIDE/DFD threat model** becomes a required artifact for money-path bounded contexts
  (ledger, transaction, payment, lending, SCA, consent) and for any new service. Stored as
  `docs/threat-models/<service>.md`, reviewed in PR, referenced from the service ADR.
- `rules.yaml` (ADR-0029 D1) gains a rule: a new money-path service or a change to its trust boundary
  requires an updated threat model — enforced by a CI check the same way the `api-contract` gate works.

> **Realization (2026-06-02, D2 coverage gate).** The existence half of this gate is live:
> `openbank-infra/scripts/check-threat-models.py` (stdlib-only) reads `rules.yaml:
> money_path_services` and fails CI if any money-path service lacks a *structured* threat
> model at `docs/threat-models/<service>.md` (non-trivial + mentions STRIDE — a stub does
> not satisfy it). It runs in the `Validate manifests` job, which is a **required** check
> (ADR-0042 Phase 1), so coverage now ratchets: adding a money-path service or deleting/
> stubbing its model blocks merge. All **13** money-path services pass today. The stronger
> "a change to a money-path trust boundary requires an *updated* model" rule (git-diff
> aware) remains follow-up.

### D3 — Deeper testing: mutation + DAST

- **Mutation testing (pitest)** on money-path domain modules: a green test suite that survives mutants is
  a false sense of safety. Mutation score (not just line coverage) becomes the quality bar for
  `openbank-libs` lending/ledger primitives and the owning services. Line coverage stays as the cheap
  floor; mutation is the depth gate on critical math.
- **DAST** (OWASP ZAP baseline) against ephemeral service instances in CI, seeded from each service's
  OpenAPI spec, on a scheduled cadence (not every PR — cost). Findings feed the D1 lifecycle.

> **Realization (2026-07-07, mutation re-verified + DAST first cut, issue #265).** Mutation
> testing: the fleet rollout (`.github/workflows/pitest.yml`, #317/#318) already covers 9/15
> money-path services with substantive domain math (ledger, balance, account, transaction,
> fx, sepa-payment, sepa-instant, domestic-payment, fraud); re-verified against a fresh
> `origin/main` checkout that the remaining 6 (sca, consent, billing, clearing, swift,
> lending) still have only 1-2 domain files of plain data models, so the ADR-0063
> thin-domain exclusion still holds — no expansion needed at this time, only confirmed.
> DAST: `.github/workflows/dast-zap-baseline.yml` runs OWASP ZAP baseline
> (`zaproxy/action-baseline`, pinned) weekly against `openbank-ledger-service` (booted via the
> existing `openbank-infra/docker-compose.yml`), scanning its documented unauthenticated
> public paths. Report-only (`fail_action: false`), feeding the D1 lifecycle. Full
> `customer-edge` (the true internet-facing edge, ADR-0065) and an authenticated crawl remain
> follow-up — `customer-edge` is a gitops-only proxy with no Gradle/image build in this repo,
> and fleet-wide boot is too heavy for a CI job.

### D4 — Admission control: verify provenance at deploy

- The GitOps deploy path (ArgoCD, ADR-0027) gains an **admission policy** (Kyverno or OPA/Gatekeeper)
  that **rejects any image lacking a valid cosign signature and SLSA provenance attestation** produced by
  ADR-0029 D2. This is the enforcement point that makes the signed chain meaningful — provenance that is
  produced but never checked is theatre.
- Policy is **policy-as-code**, versioned, and itself part of the audit trail.

> **Realization (2026-06-03, D4 admission controller + audit policy).** Kyverno is
> the chosen engine (`gitops/apps/kyverno.yaml`, fail-open webhook so a Kyverno
> outage never blocks the cluster). The `verify-openbank-image-signatures`
> ClusterPolicy (`gitops/components/kyverno/`) requires a valid Cosign signature on
> every `…/openbank-*` image — deployed in **Audit** mode so it breaks nothing and
> instead emits PolicyReports listing the (currently 100%) unsigned images, making
> the supply-chain gap measurable. Remaining for full D4: (1) choose a Cosign trust
> root — keyless OIDC or AWS KMS (deferred decision, ADR-0029 signing); (2) sign
> images in CI; (3) set the real attestor identity + flip the policy to **Enforce**.
> Until then nothing signs images, so enforcing would (correctly) block every
> deploy — hence Audit first.

### D5 — Runtime SBOM drift detection

- A scheduled job re-scans running images and diffs their SBOM against the signed release SBOM, flagging
  drift (base-image CVEs introduced post-release, unexpected packages). Drift surfaces in the admin UI
  and feeds the D1 lifecycle.

### D6 — Phasing

Sequenced after / alongside ADR-0029 because several items consume its outputs:

1. **VEX + vuln lifecycle** (D1) — highest signal-to-noise win; needs only the existing scanners + the
   ADR-0029 evidence bundle.
2. **Admission control** (D4) — switch on once ADR-0029 D2 signs images; cheap, high audit value.
3. **Threat models** (D2) — money-path services first, then backfill.
4. **Mutation + DAST** (D3) and **runtime drift** (D5) — depth controls, rolled out by criticality.

## Alternatives considered

- **Treat every scanner finding as blocking, no VEX.** Pros: simplest. Cons: at 30 services the false-
  positive volume makes the gate get ignored or bypassed — the worst outcome. Rejected — VEX-filtered,
  SLA-tracked triage (D1).
- **Threat-model everything up front.** Pros: complete. Cons: unrealistic, would stall delivery.
  Rejected — money-path-first, enforced incrementally (D2).
- **Rely on image scanning at registry only, skip admission control.** Cons: scanning ≠ enforcement; an
  unsigned image can still deploy. Rejected — admission policy is the enforcement point (D4).
- **Push all of this into ADR-0029.** Cons: conflates governance/release with security lifecycle; makes
  one ADR un-reviewable and couples two independently shippable axes. Rejected — sibling ADRs that share
  the provenance interface.

## Consequences

**Positive**
- Detection becomes managed risk: every finding has an owner, SLA, and a VEX-justified disposition.
- The signed chain from ADR-0029 is *enforced* at deploy, not merely produced.
- Money-path code gets design-phase threat analysis and behavior-validating (mutation) tests, not just
  line coverage.
- Runtime drift is caught, closing the gap between "signed at release" and "safe weeks later".

**Negative**
- Triage, threat models and VEX are ongoing human effort; the process must stay lightweight or it rots.
- Admission control can block deploys if signing (ADR-0029) regresses — a hard dependency that must be
  reliable.
- Mutation testing and DAST add CI cost; scoped to money-path / scheduled to contain it.

**Neutral**
- Reuses existing tools (Trivy, CodeQL, OPA, Vault) plus cosign/Kyverno/pitest/ZAP — no wholesale new
  security stack.

## Compliance impact

- PCI DSS: Req. 6.2/6.3 (vuln management, secure SDLC), 11.x (testing) — directly supported.
- DORA:    Art. 8–10 (ICT risk + change), Art. 24–27 (testing, incl. advanced/TLPT), Art. 28–30
  (third-party / supply-chain risk) — directly supported by VEX, threat models, DAST, admission control.
- GDPR:    Art. 32 (security of processing) — supported via threat modeling of personal-data flows.
- PSD2:    SCA/RTS robustness benefits from threat models on SCA/consent paths.
- CNB:     supports auditability and operational-resilience expectations.

## References

- ADR-0029 — Versioning, release and governance as code (produces the signed provenance this ADR enforces).
- ADR-0017 — Secrets via Vault (signing-key custody).
- ADR-0018 — OPA for fine-grained authz (reused for admission policy option).
- ADR-0020 — Code coverage Kover floor (mutation testing extends this for money-path depth).
- ADR-0027 — Cloud-agnostic substrate / GitOps (admission control plugs into this deploy path).
- NIST SP 800-218 (SSDF), SLSA framework, OWASP SAMM/DSOMM — external SSDLC references.
- `.github/workflows/security.yml` — existing detection this ADR wraps in a managed lifecycle.
