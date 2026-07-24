# OpenSSF Best Practices — Silver → Gold gap analysis

> Assessment of this repository against the [OpenSSF Best Practices Gold criteria](https://www.bestpractices.dev/en/criteria/2),
> to make the path to Gold concrete. **Positioning, not a submission** — the badge is submitted from
> the maintainer's BadgeApp account; several criteria are attestations only the maintainer can make.
> Verified against `origin/main` (SPDX coverage sampled, contributor list from `git log`, coverage
> floor from the kover config).

## Headline

The platform **already meets almost every security and quality-infrastructure Gold criterion** — it
is a security-heavy codebase (Falco, mesh mTLS, OPA, signed SLSA evidence, an external pentest,
ZAP + ClusterFuzzLite dynamic analysis). Gold is blocked on **three things that share one root**
plus a coverage push:

1. **bus factor ≥ 2**, 2. **two-person review (≥50% of changes reviewed by a non-author)**,
3. **two unassociated significant contributors** — all three fail for the same reason: a **single
maintainer** (`CODEOWNERS` is `@JiRaska` on every path; the git log is JiRaska + bots). Plus
4. **90% statement / 80% branch coverage** (the current ratchet floor is far lower).

**The first three are not solvable in code.** One added external reviewer/maintainer unlocks all
three at once — it is the single highest-leverage, non-purchasable action for the whole
credibility roadmap.

## Criterion-by-criterion

### Prerequisites
| Criterion | State | Action |
|---|---|---|
| `achieve_silver` | ⚠️ ~84% | Close the remaining Silver criteria (owner, BadgeApp) |
| `bus_factor` (≥2) | ❌ | **Add a second maintainer / external review pool** (owner) |
| `contributors_unassociated` (2+ significant) | ❌ | Same root — grow unassociated contribution |

### Basics
| `copyright_per_file` / `license_per_file` (SPDX) | ✅ | Sampled 52/52 in ledger; confirm fleet-wide, then attest |

### Change control
| `repo_distributed` (git) | ✅ | Attest |
| `small_tasks` | ✅ | `good first issue` label exists |
| `require_2FA` / `secure_2FA` | ✅ (assumed) | Owner attests GitHub 2FA is enforced (cryptographic, not SMS) |

### Quality
| `code_review_standards` | ⚠️ | CONTRIBUTING.md + GOVERNANCE.md document the flow; point the criterion at them |
| `two_person_review` (≥50%) | ❌ | **Bus-factor root** — needs a second human reviewer |
| `build_reproducible` | ⚠️ | `verification-metadata.xml` byte-pins + fast-jar help; either finish reproducibility or claim N/A with justification |
| `test_invocation` (`./gradlew test`) | ✅ | Attest |
| `test_continuous_integration` | ✅ | Extensive CI — attest |
| `test_statement_coverage90` | ❌ | Ratchet floor ~55%; raise over time (money-path first) |
| `test_branch_coverage80` | ❌ | Same |

### Security
| `crypto_used_network` / `crypto_tls12` | ✅ | TLS + Istio mTLS |
| `hardened_site` | ⚠️ | Confirm security headers on open-bank.tech (CSP/HSTS) |
| `security_review` (≤5y) | ✅ | External pentest, ADR-0080 |
| `hardening` | ✅ | Falco, NetworkPolicies, OPA, seccomp, PSS-restricted |
| `dynamic_analysis` | ✅ | ZAP baseline + schemathesis + ClusterFuzzLite |

## Recommended order

1. **Add a second reviewer/maintainer** — unlocks `bus_factor`, `two_person_review`,
   `contributors_unassociated` (3 criteria, 1 action) and is a prerequisite for the whole Gold push
   *and* the OSTIF-style external audit.
2. **Close Silver to 100%** — small, owner-driven BadgeApp work.
3. **Attest the already-met security/infra criteria** — most of Gold is a form-fill once (1).
4. **Coverage ratchet toward 90/80** — the one real engineering effort; sequence money-path first.
