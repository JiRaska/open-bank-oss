---
date: 2026-09-03
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [security, testing, observability, compliance, ci]
summary: "Security Excellence roadmap: extend the defensive stack (scans, gates, signatures) with an offensive testing layer, security-native observability signals, and shared security primitives in openbank-libs-runtime, phased Q4/2026-Q2/2027."
followup: "Tracking issue #8488 covers CRA; this roadmap is tracked in a new umbrella issue per phase. New third-party tooling (Semgrep, KEV/EPSS feeds, OpenSSF Scorecard action) requires explicit approval before adoption."
---

# ADR-0279 — Security Excellence roadmap — offensive testing, security observability and shared security primitives

## Context

The platform's security posture is overwhelmingly **defensive**: CI scans (CodeQL, Trivy,
Gitleaks), release evidence (signed SBOM/VEX/SLSA, ADR-0278), runtime policies (Kyverno,
Falco, OPA, default-deny NetworkPolicies), and the Security Excellence console
(`/security/excellence`, runbook 0016) aggregating them. Defence-in-depth is necessary
but not sufficient: none of these controls *attacks us*. The defect history in
AGENTS.md shows the recurring classes (authz edge cases, nullable JAX-RS params,
scheduler context loss, silent no-op success flags) that only adversarial testing
surfaces reliably.

Three gaps stand out when the ecosystem is read as one system rather than per-layer:

1. **No offensive layer.** The external pentest was a one-off; schemathesis fuzzing runs
   point-wise; no internal red-team cadence, no adversarial contract tests, no security
   load scenarios in the existing k6 gates.
2. **Observability has no security signal.** Tempo/Loki/Alertmanager are built and
   alert on liveness and workflow staleness (ADR-0237), but no span attribute, log
   rule, or alert answers "is someone probing us right now". The audit register exists
   and nothing correlates it.
3. **Security primitives are re-implemented per service.** AGENTS.md documents the same
   mistakes shipped three and five times (non-null JAX-RS params, `Instant.EPOCH`
   defaults, noop-reports-success). The fixes that *stuck* were the ones that moved
   into `openbank-libs-runtime` (NulByteGuards, GenericExceptionMapper) or became CI
   gates — prose never worked.

## Decision

We adopt a 26-point Security Excellence roadmap in four workstreams, phased
Q4/2026–Q2/2027, each item landing as a lib primitive, a CI gate, or an observability
signal — never as prose alone:

**WS1 — Offensive testing** (testing ↔ security): internal pentest cadence; continuous
DAST matrix across all services; adversarial Pact contract tests; k6 security
scenarios; a mandatory regression test per security incident (CI-gated); Semgrep SAST
for fleet-specific patterns (pending approval, see below); security-targeted mutation
testing on authz code.

**WS2 — Security observability** (observability ↔ security): security span attributes
(authz decision, delegation chain, risk score) in traces; a Loki security rule pack
(authz-denial anomalies, RBAC changes outside change windows); honeytokens and honey
endpoints in sandbox wired to alerts; runtime-vs-release SBOM drift with KEV/EPSS
exploitability triage; security SLOs (CVE MTTR, signed-SBOM coverage, vuln age) as
first-class metrics on the hub.

**WS3 — Supply chain & release hardening**: VEX auto-triage (KEV/EPSS) opening hotfix
PRs; SLSA 3 end-to-end with Kyverno verification on all namespaces; dependency
freshness scoring; container attack-surface audits.

**WS4 — Identity, zero trust & governance**: mTLS/NetworkPolicy coverage as KPIs;
workload identity everywhere (no long-lived keys); WebAuthn-only maintainer auth; OPA
on IaC with expiring exceptions; security chaos drills merged with DR drills
(ADR-0242); purple-team tabletop extending runbook 0018 through the real alert
pipeline; per-service security scorecard on the hub; threat-model diff gate
enforcement; VDP/bug-bounty readiness; security champions with the AGENTS.md pitfalls
turned into PR-checklist automation.

**Shared primitives live in `openbank-libs-runtime`, not a new module.** The
`com.openbank.libs.security` package already exists and is auto-consumed by all ~54
service build files via project dependency + Jandex + `beans.xml`; a new
`openbank-libs-security` module would require editing every consumer, the services-ci
regexes and `libs-change-dependents.sh`, and is justified only for optional-dependency
producers (the libs-temporal precedent). First primitives: security telemetry helpers
(span attributes + metrics), a honeytoken request filter, and security-SLO metric
registration.

**New tooling requiring explicit approval before adoption** (none is adopted by this
ADR): Semgrep (SAST, AGPL engine / commercial rules), KEV/EPSS public feeds (no cost,
external dependency), OpenSSF Scorecard GitHub Action. Everything else builds on
already-deployed technology.

## Alternatives considered

- **Buy a CNAPP/ASPm platform instead of building.** Rejected for the core: our
  evidence model (signed bundles, gates, hub) is repo-native; a platform would see
  outputs, not controls. Re-evaluate if the self-built correlation (WS2) proves
  unmaintainable.
- **A new `openbank-libs-security` module.** Rejected for now (above); revisit the day
  a primitive needs a dependency no service-wide module may carry.
- **Offensive layer outsourced entirely (external pentest only).** Rejected: annual
  snapshots cannot gate a pipeline that ships daily; external tests stay as
  validation, not detection.

## Consequences

**Positive**
- Every AGENTS.md pitfall class gains either a lib primitive or a gate — the two
  mechanisms that demonstrably stuck.
- The hub shifts from a display of defensive scans to a control surface with
  offensive and observability signals.
- CRA/DORA evidence (ADR-0278) gains continuous, machine-generated backing.

**Negative**
- Loki/Tempo rule maintenance joins the recurring compliance surface.
- Honeytokens and red-team cadence are operational commitments, not code.

**Neutral**
- Phasing: Q4/2026 = WS1 foundation + WS2 signals (quick wins first); Q1/2027 = WS3 +
  scorecard; Q2/2027 = WS4 governance cadence. Each phase files its own umbrella
  issue; this ADR tracks none directly.

## Compliance impact

- CRA: WS2/WS3 strengthen Annex I Part II evidence; no new duties.
- DORA: purple-team drill shares the ADR-0242 DR calendar; incident register unchanged.
- GDPR: security span attributes and Loki rules must not carry PII — attribute keys
  carry identifiers and decisions, never payloads (same bound as ADR-0274 RUM).
- PSD2/PCI DSS/CNB: not applicable.

## References

- ADR-0030 (threat models), ADR-0237 (scheduler liveness), ADR-0242 (DR drills),
  ADR-0241 (alert hygiene), ADR-0274 (RUM privacy bound), ADR-0278 (CRA),
  runbooks 0016–0018.
- `.github/workflows/security.yml`, `openbank-libs-runtime` (`com.openbank.libs.security`,
  `observability`, `api.error` packages).
