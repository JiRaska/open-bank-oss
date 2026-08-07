# OpenSSF Best Practices — Silver → Gold gap analysis

> Assessment of this repository against the [OpenSSF Best Practices Gold criteria](https://www.bestpractices.dev/en/criteria/2),
> to make the path to Gold concrete. **Positioning, not a submission** — the badge is submitted from
> the maintainer's BadgeApp account; several criteria are attestations only the maintainer can make.
>
> **Every justification in the tables below must name something measurable — a repo path, an ADR, a
> live HTTP response, or an issue number — and must have been measured, not assumed.** This document
> feeds a public attestation to a third party, and an assessor checks the *evidence*, not the tick.
> A justification naming a control that does not exist is worse than an honest gap.
> Evidence discipline is enforced by `.github/scripts/check-openssf-gold-evidence.py`
> (gate `openssf-gold-evidence`).

## Headline

The platform **already meets almost every security and quality-infrastructure Gold criterion** — it
is a security-heavy codebase (Falco, default-deny NetworkPolicies, OPA, signed SLSA evidence, an
external pentest, ZAP + ClusterFuzzLite dynamic analysis). Gold is blocked on **three things that
share one root** plus a coverage push:

1. **bus factor ≥ 2**, 2. **two-person review (≥50% of changes reviewed by a non-author)**,
3. **two unassociated significant contributors** — all three fail for the same reason: a **single
maintainer** (`CODEOWNERS` is `@JiRaska` on every path; the git log is JiRaska + bots). Plus
4. **90% statement / 80% branch coverage** (the current per-service floors are far lower).

**The first three are not solvable in code.** One added external reviewer/maintainer unlocks all
three at once — it is the single highest-leverage, non-purchasable action for the whole
credibility roadmap.

> **No service mesh is deployed.** There is an `openbank-infra/k8s/base/istio.yaml` manifest, but the
> control plane has never been bootstrapped: the live cluster has **zero** Istio CRDs and zero Istio
> namespaces (measured 2026-08-06), and no ArgoCD Application references the manifest. In-cluster
> isolation today is **NetworkPolicy-based, not mesh-based** — as `README.md`, `docs/ARCHITECTURE.md`
> and `docs/strategy/09-roadmap-M1-M7.md` already state. Tracked by **#1914**. No criterion below may
> cite mesh mTLS as evidence.

## Criterion-by-criterion

Legend — ✅ met, with measured evidence · ⚠️ partial, or met only by an owner attestation this repo
cannot verify · ❌ not met.

### Prerequisites
| Criterion | State | Evidence / action |
|---|---|---|
| `achieve_silver` | ⚠️ | **Not verifiable from this repo** — Silver progress lives in the maintainer's BadgeApp account. Owner to read the current percentage off BadgeApp and close the remainder |
| `bus_factor` (≥2) | ❌ | Measured: `CODEOWNERS` names `@JiRaska` on every path, 0 lines naming anyone else. **Add a second maintainer / external review pool** (owner) |
| `contributors_unassociated` (2+ significant) | ❌ | Measured: `git log origin/main` has exactly one human author address; every other author is a bot. Same root — grow unassociated contribution |

### Basics
| Criterion | State | Evidence / action |
|---|---|---|
| `copyright_per_file` / `license_per_file` (SPDX) | ✅ | Measured fleet-wide: 2893 of 2894 `.kt` files carry `SPDX-License-Identifier`. Enforced continuously by `.github/scripts/check-license-headers.py` (gate `license-header-consistency`), which checks headers against the `agpl_modules` set in `openbank-libs/governance/rules.yaml` |
| `small_tasks` | ⚠️ | The label exists but is spelled `good-first-issue` in `.github/labels.yml`, and **0 open issues currently carry it** (measured 2026-08-06). The criterion asks the project to *identify* small tasks — label the backlog before claiming this |

### Change control
| Criterion | State | Evidence / action |
|---|---|---|
| `repo_distributed` (git) | ✅ | The project is a public git repository with its full history at https://github.com/JiRaska/open-bank-oss |
| `require_2FA` / `secure_2FA` | ⚠️ | **Owner attestation only** — GitHub account 2FA settings are not observable from this repository. Previously scored ✅ "(assumed)"; an assumption is not evidence |

### Quality
| Criterion | State | Evidence / action |
|---|---|---|
| `code_review_standards` | ⚠️ | `CONTRIBUTING.md` and `GOVERNANCE.md` both exist and document the flow; point the criterion at them. Partial only because the review itself has no second human (see `two_person_review`) |
| `two_person_review` (≥50%) | ❌ | **Bus-factor root** — needs a second human reviewer |
| `build_reproducible` | ⚠️ | **Artefact present, enforcement pending.** `gradle/verification-metadata.xml` byte-pins dependencies, but `gradle.properties` sets `org.gradle.dependency.verification=lenient` and the metadata sets `<verify-signatures>false</verify-signatures>` — so a checksum mismatch warns rather than fails, and signatures are not checked at all. The lenient→strict flip is tracked by **#1915** (target 2026-09-30). Do not claim this until that lands |
| `test_invocation` (`./gradlew test`) | ✅ | The Gradle wrapper is committed (`gradle/wrapper/gradle-wrapper.properties`) and `CONTRIBUTING.md` documents the command: "Ensure tests pass locally (`./gradlew test` for JVM, `npm test` for Admin UI)". Scored on the documented entry point, not on a clean fleet-wide run |
| `test_continuous_integration` | ✅ | `.github/gates/gates.yaml` declares 110 CI gates run per PR, plus per-service build/test workflows under `.github/workflows/` |
| `test_statement_coverage90` | ❌ | Measured: 60 per-module kover `minValue` floors span 0–90 with a median of 55; **58 of 60 are below 90**. Ratchet upward, money-path first |
| `test_branch_coverage80` | ❌ | Same ratchet, same floors |

### Security
| Criterion | State | Evidence / action |
|---|---|---|
| `crypto_used_network` / `crypto_tls12` | ✅ | **Edge TLS only.** Ingress certificates are issued by cert-manager from `letsencrypt-prod` (`openbank-infra/gitops/components/*/ingress.yaml`); the static site fronts CloudFront with `minimum_protocol_version = "TLSv1.2_2021"` (`openbank-infra/aws/modules/static-site/main.tf`). Verified live: `https://open-bank.tech` and `https://admin.open-bank.tech` both answer over HTTP/2 TLS (2026-08-06). **Not** mesh mTLS — see the note above and **#1914** |
| `hardened_site` | ✅ | Measured live 2026-08-06: both `https://open-bank.tech` and `https://admin.open-bank.tech` return `strict-transport-security: max-age=63072000; includeSubDomains; preload`, a `content-security-policy` with `object-src 'none'` and `base-uri 'self'`, and an `x-frame-options` header |
| `security_review` (≤5y) | ✅ | External web pentest of `admin.open-bank.tech`, 2026-06-10, recorded in `docs/adr/0080-pentest-remediation-p0-p2.md`. Note that ADR's own `delivery-status: partial` — the *review* is what this criterion asks for, remediation is tracked separately |
| `hardening` | ✅ | Measured across `openbank-infra/gitops/`: 64 files declaring `kind: NetworkPolicy` (default-deny fleet-wide), 102 files setting `seccompProfile`, 42 namespaces with `pod-security.kubernetes.io/enforce: restricted`, plus per-service OPA bundles under `openbank-infra/gitops/components/`. Falco is deployed (its namespace is live in the cluster) |
| `dynamic_analysis` | ✅ | `.github/workflows/dast-zap-baseline.yml` (ZAP baseline), `.github/workflows/api-fuzz.yml` + `api-fuzz-authenticated.yml` (schemathesis), `.clusterfuzzlite/` + `.github/workflows/cflite-pr.yml` (ClusterFuzzLite) |

## What was measured, and what was not

Measured directly for this revision (2026-08-06): the cluster mesh state (`kubectl get crd` / `get ns`),
the Gradle verification mode, SPDX coverage across all 2894 `.kt` files, the kover floor distribution,
`CODEOWNERS`, `git log` authorship, the label file and open-issue label counts, the presence of every
workflow and manifest cited above, and live HTTP response headers for both public sites.

**Taken on trust / not verifiable here:** `achieve_silver` (BadgeApp account state) and
`require_2FA` / `secure_2FA` (GitHub account settings). Both are now scored ⚠️ rather than ✅ for
exactly that reason.

**Possible residual overstatement:** `hardening` and `dynamic_analysis` are scored on the *existence
and wiring* of each named control, not on its runtime efficacy — a NetworkPolicy that exists is not
proof that it denies the right traffic, and a ZAP job that runs is not proof it has meaningful
coverage. `test_invocation` is scored on the documented command, not on a clean fleet-wide run.

## Recommended order

1. **Add a second reviewer/maintainer** — unlocks `bus_factor`, `two_person_review`,
   `contributors_unassociated` (3 criteria, 1 action) and is a prerequisite for the whole Gold push
   *and* the OSTIF-style external audit.
2. **Close Silver to 100%** — small, owner-driven BadgeApp work.
3. **Attest the already-met security/infra criteria** — most of Gold is a form-fill once (1).
4. **Land #1915** (dependency verification lenient→strict) to convert `build_reproducible`.
5. **Label a handful of `good-first-issue` items** to convert `small_tasks` — cheap.
6. **Coverage ratchet toward 90/80** — the one real engineering effort; sequence money-path first.

> **If anything has already been submitted to BadgeApp citing "Istio mTLS" for `crypto_used_network`
> or `crypto_tls12`, that justification is false and should be replaced with the edge-TLS evidence
> above.** Submitting or amending the badge is the maintainer's action; this document does not do it.
