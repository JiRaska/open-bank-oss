# OpenSSF Best Practices — Silver-level self-assessment

Status: the project holds the **passing** badge (100%, [project 13505](https://www.bestpractices.dev/projects/13505));
Silver sits at 13% only because the form is unfilled, not because the practices are missing.
This document maps every unfilled Silver/Gold criterion to repo evidence, as paste-ready
justifications for bestpractices.dev (only the badge-entry owner can submit them).

Legend: **Met** — paste the justification; **Unmet** — the honest gap and what would close it;
**N/A** — with the permitted justification.

## Basics / governance

| Criterion | Assessment | Justification to paste |
|---|---|---|
| `governance` | **Met** | GOVERNANCE.md documents the decision model: single-maintainer beta with ADRs (docs/adr/, 140+) as the binding decision mechanism, 2-approval rule for money-path code, 24-hour PR hold, and the documented path to distributed governance. |
| `roles_responsibilities` | **Met** | GOVERNANCE.md + CODEOWNERS define maintainer duties; money-path services require maintainer + external technical reviewer (2 approvals). |
| `access_continuity` | **Unmet** (honest) | Bus factor 1 is stated openly in GOVERNANCE.md and ADR-0146. Closing requires a second maintainer with owner access — tracked as the top governance follow-up of the 2026-07 SSDLC audit. |
| `bus_factor` | **Unmet** (honest) | Same as `access_continuity`; the criterion needs ≥2 significant contributors. |
| `documentation_roadmap` | **Met** | Governance follow-ups live as labeled GitHub issues (ADR-0052 backlog discipline); the coverage/mutation roadmap is issue #321; rules.yaml `target_enforce_date` fields (ADR-0144) are the enforcement roadmap, machine-readable. |
| `documentation_architecture` | **Met** | docs/adr/ — 140+ Architecture Decision Records with status + delivery tracking; per-service CLAUDE.md; hexagonal architecture spec in ADR-0002. |
| `documentation_security` | **Met** | SECURITY.md (disclosure policy, SLAs); docs/threat-models/ (33 STRIDE/DFD models, CI-gated for all 14 money-path services); ADR-0030 (SSDLC), ADR-0146 (incident response). |
| `documentation_quick_start` | **Met** | README quick start + CONTRIBUTING.md dev setup (JDK 25, Gradle, Docker); one-command service build. |
| `documentation_current` | **Met** | Docs gates in CI (`admin_ui_docs_currency` rule; ADR registry check); release notes generated per component by release-please. |
| `documentation_achievements` | **Met** | README badges: CI, security scan, Scorecard, Best Practices, codecov. |
| `homepage_url` / `hardened_site` / `sites_password_security` | **Met** | Hosted on github.com (HTTPS/HSTS, GitHub credential policy applies). |
| `report_url` / `vulnerability_response_process` | **Met** | SECURITY.md: private GitHub Security Advisories, response SLAs (Critical 72h/30d, High 7d/60d…), coordinated disclosure. |
| `vulnerability_report_credit` | **Met** | SECURITY.md commits to reporter credit on disclosure (add one sentence if the wording is missing). |
| `code_of_conduct` | **Met** | CODE_OF_CONDUCT.md — Contributor Covenant v2.1. |
| `dco` | **Met** | CONTRIBUTING.md requires DCO sign-off (`git commit -s`) on every commit; cryptographic signatures (`-S`) additionally enforced by the main ruleset. |
| `maintenance_or_update` | **Met** | Actively maintained (daily commits, weekly scheduled security lanes). |

## Change control

| Criterion | Assessment | Justification to paste |
|---|---|---|
| `two_person_review` | **Partially met → answer Unmet** | 2-person review is mandatory for the 14 money-path services (CODEOWNERS + ruleset); the remainder is single-maintainer-reviewed. Full criterion needs fleet-wide 2-person review — blocked on bus factor. |
| `code_review_standards` | **Met** | CONTRIBUTING.md Definition of Done + PR template checklists (security, compliance, tests) define what review must establish. |
| `small_tasks` | **Met** | Conventional-commit, per-service scoped PRs; squash-merge; fleet sweeps split one-PR-per-service (ADR-0052). |
| `require_2FA` / `secure_2FA` | **Met** | GitHub org/account enforces 2FA (confirm in settings; GitHub now mandates 2FA for contributors of this tier). |
| `version_tags_signed` | **Met** | Releases carry a cosign-signed evidence bundle (SBOM+SLSA+VEX+manifest, AWS KMS key) — stronger than a bare signed tag; verify-release-evidence.yml is the independent verifier. |
| `signed_releases` | **Met** | Same as above; release assets are cosign `sign-blob`-signed per artifact. |

## Quality

| Criterion | Assessment | Justification to paste |
|---|---|---|
| `automated_integration_testing` | **Met** | Testcontainers integration suites per service (PostgreSQL/Redpanda/Valkey, per-JVM isolation, ADR-0044); Pact consumer+provider contract tests against an in-cluster broker. |
| `regression_tests_added50` | **Met** | Bug-class regressions get dedicated CI guards (duplicate-YAML-keys, outbox dispatch flag, DomainEvent ctor, runBlocking Unit…) — regression tests are the house style. |
| `test_policy_mandated` | **Met** | CONTRIBUTING.md DoD: "Test the new behavior"; coverage is ratchet-only (never lower), enforced by koverVerify per service. |
| `test_most` | **Met** | Coverage ratchet + per-service floors (e.g. transaction 85, fraud 85, domestic-payment 75); mutation testing (pitest) on 9 money-path services weekly. |
| `test_statement_coverage80` / `test_branch_coverage80` / `test_statement_coverage90` | **Unmet** (honest) | Fleet-wide 80% statement coverage is not yet reached; the ratchet roadmap (#321) drives money-path floors to ≥70% and beyond, quarterly. |
| `coding_standards` / `coding_standards_enforced` | **Met** | detekt (maxIssues=0 vs baseline) + ktlint wired into `check`; path-scoped CI enforces on every PR. |
| `build_standard_variables` / `build_non_recursive` | **Met** | Standard Gradle conventions; convention plugins in build-logic/; no recursive make. |
| `build_preserve_debug` | **Met** | JVM bytecode retains debug info by default; fast-jar packaging does not strip. |
| `build_repeatable` | **Met** | Pinned toolchain (JDK 25), version-catalog + committed lockfiles, digest-pinned base images, host-side SBOM. |
| `build_reproducible` | **Unmet** (honest) | Bit-for-bit reproducibility is not yet verified end-to-end (timestamps in jars). Candidate follow-up: `preserveFileTimestamps=false` normalization + a rebuild-and-compare job. |
| `installation_common` / `installation_standard_variables` / `installation_development_quick` | **Met** | `docker compose up` local stack; `./gradlew :svc:build`; Codespaces badge for one-click dev env. |
| `external_dependencies` / `updateable_reused_components` / `dependency_monitoring` | **Met** | Version catalog + lockfiles; Dependabot across 7 ecosystems weekly with grouped auto-merge; Trivy + dependency-review CVE gates; (in-flight: Gradle dependency-verification checksums). |
| `interfaces_current` | **Met** | OpenAPI per service is the contract source (ADR-0005); oasdiff classification gate keeps `info.version` honest (ADR-0048 D5). |
| `internationalization` / `accessibility_best_practices` | **N/A / Met (UI)** | Server APIs are locale-neutral (ISO 4217/8601); admin-ui follows Next.js/eslint a11y defaults — answer per form guidance. |
| `documentation_quick_start` | **Met** | (see above) |

## Security

| Criterion | Assessment | Justification to paste |
|---|---|---|
| `implement_secure_design` | **Met** | ADR-0002 hexagonal + deny-by-default OPA authz (ADR-0034), four-eyes on money verbs, tamper-evident audit chain (ADR-0133), STRIDE threat models CI-gated (ADR-0030 D2). |
| `input_validation` | **Met** | Bean-validation on DTOs; schemathesis property-based fuzzing lane asserts no malformed input 5xxs (api-fuzz.yml). |
| `crypto_used_network` / `crypto_tls12` | **Met** | TLS everywhere (edge + in-cluster mTLS migration ADR-0137); TLS ≥1.2 enforced by platform defaults. |
| `crypto_algorithm_agility` | **Met** | Cosign KMS key (ECDSA-P256) rotatable by alias; Keycloak realm keys rotate (ADR-0099). |
| `crypto_certificate_verification` | **Met** | Standard JVM/quarkus TLS verification; no `trustAll` in the tree (CI-greppable). |
| `crypto_credential_agility` | **Met** | Secrets via Vault/OpenBao + External Secrets; rotation runbooks (ADR-0099); no long-lived CI credentials (GitHub OIDC → IAM). |
| `crypto_verification_private` | **Met** | Private keys never in repo (gitleaks custom rules + push protection); signing via cloud KMS only. |
| `security_review` | **Met** | External pen-test performed (P0–P2 findings remediated, README); continuous CodeQL + Trivy + weekly Scorecard; threat models per money-path service. |
| `hardening` | **Met** | Non-root containers, digest-pinned bases, Kyverno admission (signature Enforce, SBOM attestation Audit), NetworkPolicies generated from declared edges (ADR-0081), least-privilege workflow tokens. |
| `assurance_case` | **Unmet** (honest) | No single assurance-case document yet. Candidate: docs/assurance-case.md tying threat models + gates + evidence bundles into one argument. Most raw material exists. |
| `copyright_per_file` / `license_per_file` | **Met** | SPDX headers fleet-wide (`SPDX-License-Identifier` in every source file). |

## Gold-only (listed for completeness)

`contributors_unassociated`, `achieve_silver` chain, `two_person_review` fleet-wide, `test_statement_coverage90` —
all gated on the same two roots: **bus factor** and **fleet coverage depth**. They are the audit's #1 and #3
recommendations and are tracked in GOVERNANCE.md follow-ups and #321.

## How to submit

1. Open https://www.bestpractices.dev/projects/13505/edit (badge-entry owner only).
2. Paste the justifications above; mark the honest Unmets as Unmet — the form rewards honesty and
   partial credit; Silver needs ≥90% of Silver criteria met or justified-N/A.
3. Re-run after the bus-factor and coverage follow-ups land; Silver should be reachable now,
   Gold after a second maintainer joins.
