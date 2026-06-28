# OSS-readiness and public-launch hardening

Date: 2026-06-27
Status: Proposed
Author(s): @JiRaska

## Context

OpenBank is engineered to a very high bar — per-service release-please, signed
evidence bundles (SBOM + SLSA + OpenVEX via cosign/KMS), OPA/Kyverno policy-as-code,
threat-model gates, clock/UUID/DST injection guards, path-scoped per-service CI, and a
machine-readable governance source of truth (`openbank-libs/governance/rules.yaml`).
The community-health files score 100% and the `main-protection` ruleset enforces signed
commits, linear history, and required status checks.

Despite this, the repository is **not yet a genuinely best-in-class *open-source*
project**. A review on 2026-06-27 surfaced systemic gaps that are invisible from inside
the engineering workflow but obvious to an external adopter:

1. **It is not public.** The repo is `private`, with no `topics`, no `homepage`, no
   Discussions, and no public Releases. None of the OSS-styled scaffolding is reachable
   by anyone outside the org.
2. **GitHub-native security is disabled.** Dependabot *alerts*, automated security
   fixes, secret scanning + push protection, and code scanning (CodeQL) are all OFF.
   CI partially substitutes (gitleaks, Trivy), but the native layer — free on public
   repos — is a blind spot today.
3. **Repo hygiene.** CI-generated output was committed: `sbom-downloads/` (~190 MB of
   CycloneDX JSON), `reports/` (Gradle test HTML), and a stray downloaded Actions
   artifact. HEAD is being cleaned in PR #2281; the history still carries the blobs.
4. **Governance looks enforced but mostly is not.** Of ~20 rules in `rules.yaml`, only
   ~3 actually *block* (duplicate-yaml-keys, finops-lifecycle, issue-hygiene). Coverage
   ratchet, API-contract, db-migration, threat-model, and pitest are `advisory`/`planned`.
   ADR `Status:` is free text (`Accepted (2026-06-14 — implemented…)`), not parseable.
5. **CI/CD gaps vs the top OSS bar.** No post-build container image scan, no OpenSSF
   Scorecard, no Dependabot auto-merge (15 dependency/feature PRs open), pitest advisory.
6. **Bus factor.** `CODEOWNERS` is a single person (`@JiRaska`) across money-path code,
   so the governance "2 approvals" rule cannot be satisfied by distinct humans today.
7. **Onboarding docs.** README is 313 lines with a large "what doesn't work" section and
   no architecture diagram or UI screenshot; `CONTRIBUTING.md` defers local dev setup to
   the README; there is no `ARCHITECTURE.md` / `DEPLOYMENT.md` for newcomers.

We want OpenBank to be the reference open-source bank. That is a *go-public* decision
with hard ordering constraints (history rewrite must precede publication; security
toggles must be on before the first external clone), so it deserves a recorded plan
rather than ad-hoc fixes.

## Decision

We will run a **phased OSS-readiness program** with a single, ordered go-public cutover.
Each workstream below becomes a tracked governance-task issue (ADR-0052); this ADR is the
decision and the ordering contract, not the backlog itself.

**Workstream A — Hygiene & history (P0).**
- A1: Untrack committed CI artifacts + ignore them. *(Done: PR #2281.)*
- A2: At the cutover, run `git filter-repo --path sbom-downloads --path reports
  --invert-paths` to reclaim the ~190 MB. This rewrites every commit SHA, so it runs
  **once**, after open PRs are drained and all working clones (Mac mini / secondary
  instances) are ready to re-clone. It invalidates existing signature verification on
  rewritten commits — acceptable for a one-time pre-publication rewrite.

**Workstream B — GitHub-native security (P0, flip at cutover).**
- Enable Dependabot alerts + automated security fixes, secret scanning + push
  protection, and CodeQL code scanning. These are free on public repos and run
  *alongside* the existing CI scanners, not instead of them.

**Workstream C — Discoverability (P1).**
- Set `description`, `topics` (`fintech`, `quarkus`, `kotlin`, `hexagonal-architecture`,
  `psd2`, `open-banking`, `event-driven`), `homepage` → `admin.open-bank.tech`; enable
  Discussions; seed `good first issue` labels and a handful of starter issues.

**Workstream D — CI/CD enforcement (P1).**
- D1: Add a post-build container image scan (Trivy/Grype) to the deploy path.
- D2: Flip coverage ratchet from advisory to a blocking gate (ratchet-only, never lower).
- D3: Add an OpenSSF Scorecard workflow + README badge.
- D4: Enable Dependabot auto-merge for patch/minor on a pinned-policy allowlist.

**Workstream E — Governance enforcement & ADR hygiene (P1).**
- E1: Flip the prepared `advisory` rules in `rules.yaml` to `enforce` where the check
  already exists (api-contract, db-migration, threat-model, pitest).
- E2: Move ADR `Status` into parseable frontmatter (`status: accepted`,
  `implemented: 2026-06-14`) and add a CI check.

**Workstream F — Maintainership (P1).**
- Either distribute `CODEOWNERS` across ≥2 maintainers, or publish a `GOVERNANCE.md`
  that states the single-maintainer reality honestly and defines how the "2 approvals"
  money-path rule is met (e.g. external reviewer rotation) before accepting outside PRs.

**Workstream G — Onboarding docs (P2).**
- Slim the README to a landing page; add `docs/ARCHITECTURE.md` (C4 + human summary of
  the founding ADRs) and inline dev-setup in `CONTRIBUTING.md`.

**Cutover ordering (hard constraints):**
`drain/close open PRs` → `A2 filter-repo` → `force-push rewritten history` →
`re-clone all working copies` → `B enable native security` → `C set metadata` →
`flip visibility to public`.

## Alternatives considered

- **Do nothing / stay private** — keeps the status quo. Rejected: contradicts the stated
  goal of being the reference open-source bank; the security blind spots persist.
- **Big-bang: flip to public now, fix afterwards** — fastest to "public". Rejected:
  publishes the ~190 MB junk history permanently (filter-repo after publication is
  hostile to anyone who already forked/cloned), and exposes the repo with native
  security scanning still off.
- **Per-fix PRs with no recorded plan** — already how A1 happened. Rejected as the *whole*
  approach: the cutover has ordering constraints (history before publication, security
  before first clone) that must be written down and sequenced, not rediscovered.

## Consequences

**Positive**
- A genuinely publishable, top-decile OSS repository with native + CI security in depth.
- ~190 MB reclaimed from history; faster clones; clean first impression.
- Governance becomes real (blocking) rather than aspirational (advisory).
- Clear, ordered runbook for the irreversible go-public step.

**Negative**
- The `filter-repo` rewrite is disruptive: it breaks open PRs and signature verification
  on rewritten commits and forces every clone to re-clone. Must be coordinated.
- Flipping advisory rules to `enforce` will block PRs that previously passed; expect a
  short remediation tail across the fleet.

**Neutral**
- Enabling GHAS-style features is free on public repos; on private it would be paid, so
  B is naturally sequenced with the visibility flip.

## Compliance impact

- PCI DSS: not applicable (no cardholder data in the repo itself).
- DORA:    positive — improves ICT supply-chain transparency (SBOM/provenance) and
  vulnerability management posture; no new obligation created.
- GDPR:    not applicable (no personal data; history rewrite touches only build output).
- PSD2:    not applicable.
- CNB:     not applicable.

## References

- PR #2281 — untrack CI-generated artifacts (Workstream A1)
- ADR-0029 — governance as code (per-service SemVer, derive/enforce/display)
- ADR-0030 — money-path controls (2 approvals + threat model)
- ADR-0048 — release vs API-contract version axes
- ADR-0052 — issues as the actionable backlog
- `openbank-libs/governance/rules.yaml` — authoritative rule set
- OpenSSF Scorecard — https://github.com/ossf/scorecard
