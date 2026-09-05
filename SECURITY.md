# Security Policy

OpenBank is open-source banking platform software. While it is **not production-ready** and **not licensed to operate as a bank**, the code is designed around the defence-in-depth security practices expected of banking software. We take security reports seriously.

## Supported Versions

Only the `main` branch and the latest tagged release receive security fixes during the beta / early-access phase.

| Version | Supported |
|---------|-----------|
| `main`  | ✅        |
| latest tag | ✅     |
| anything else | ❌  |

### Support period and end-of-support (per released component)

OpenBank releases **per component** (each `openbank-*` service and the admin UI has its
own `version.txt` and its own tag, `<component>-v<version>`), so support is declared per
component, not per platform:

- **Beta phase (all components at 0.x):** a component release is supported for
  **12 months from its release date, or 90 days after the release that supersedes it,
  whichever is later**. Security fixes land on `main` and are delivered by the next
  tag of that component; during beta we do not maintain parallel patch branches.
- **From the first production-ready (1.x) release of a component:** the support period
  extends to **5 years from that release's date**, aligned with the CRA default
  (Regulation (EU) 2024/2847, Art. 13(8)) — with free security updates for the whole
  period.
- **End-of-support is data, not prose:** every release carries its evidence bundle
  (`.evidence.json`, see the CRA section below); the end-of-support date of a release
  is derived mechanically from the two rules above against its release date, and the
  formal EOS field will be added to the evidence bundle before the first 1.x release
  (tracked in #8488).

## Reporting a Vulnerability

**DO NOT** open a public GitHub issue for security vulnerabilities.

### Channel

Report privately via one of:

1. **GitHub Security Advisories** (preferred):
   https://github.com/JiRaska/open-bank-oss/security/advisories/new

   GitHub Security Advisories provide a private collaboration space where maintainers and reporters can discuss, develop, and publish coordinated fixes.

2. **Email** (no GitHub account needed):
   security@open-bank.tech

   This mailbox is unencrypted by default. If your report involves live exploit details you consider highly sensitive, say so in your first message and we'll agree on a secure delivery method before you send them.

Machine-readable contact metadata is published per [RFC 9116](https://www.rfc-editor.org/rfc/rfc9116) at
[`open-bank.tech/.well-known/security.txt`](https://open-bank.tech/.well-known/security.txt).

### What to include

- Affected component (service name, file path, commit hash)
- Reproduction steps (minimal PoC if possible)
- Impact assessment (CIA triad: confidentiality / integrity / availability)
- Suggested remediation (optional)
- Your contact details and any disclosure preferences (anonymity is respected)

### Response SLA (best-effort, pre-alpha)

| Severity | Initial response | Triage | Fix target |
|----------|------------------|--------|------------|
| Critical (RCE, auth bypass, data leak) | 72 h   | 7 days | 30 days     |
| High     | 7 days | 14 days | 60 days   |
| Medium   | 14 days | 30 days | 120 days |
| Low      | 30 days | 60 days | next release |

> SLAs are best-effort and reflect the beta, community-maintained nature of the project (single maintainer). Once OpenBank reaches a production-ready release and gains additional maintainers, SLAs will tighten significantly.

### Coordinated Disclosure

We follow [coordinated disclosure](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure): we will agree a public disclosure date with the reporter, typically after a fix is released or 90 days from the report, whichever is sooner.

Reporters are credited in release notes and the security advisory unless anonymity is requested.

## Cyber Resilience Act (EU) 2024/2847 Readiness

OpenBank is open-source software in beta and is **not yet placed on the market** as a product with digital elements, so CRA manufacturer duties do not yet bind us. We nevertheless track readiness deliberately — see [ADR-0278](docs/adr/0278-cyber-resilience-act-readiness-secure-sdlc-sbom-and-vulnerability-reporting-duties.md) — and this policy already provides the CRA-shaped surface:

- **Single point of contact for vulnerabilities** (Art. 11, Annex I): the channels above (GitHub Security Advisories, security@open-bank.tech, `security.txt`) are the single intake.
- **Coordinated vulnerability disclosure** (Art. 11): the Coordinated Disclosure section above is the policy; we accept reports, remediate, and disclose on an agreed timeline.
- **Vulnerability handling across the SDLC** (Annex I Part I): dependency scanning, secret scanning, CodeQL, cosign-signed CycloneDX SBOM attached to every GitHub Release, and signed commits — see "What Security Controls Are in Place" below.
- **Incident and actively-exploited-vulnerability reporting** (Art. 14): the operational procedure lives in [runbook 0017](docs/runbooks/0017-cra-article-14-reporting.md) (24 h early warning / 72 h notification to the designated CSIRT and ENISA once applicable), rehearsed per [runbook 0018](docs/runbooks/0018-cra-art14-tabletop-exercise.md).
- **Support period** (Art. 13(8)): declared per component in "Support period and end-of-support" above — 12 months / 90-days-after-superseded during beta, extended to the CRA ≥ 5-year default from each component's first production-ready (1.x) release.

## Out of Scope

The following are explicitly out of scope for this project:

- Issues in third-party dependencies — please report to the upstream project first, then notify us with the upstream tracker link.
- Vulnerabilities that require physical access to the infrastructure of a deployer.
- Social engineering of contributors or maintainers.
- Denial of Service via volumetric attacks against shared community infrastructure.
- Issues that only manifest in unsupported configurations or modifications.
- Findings against deployments operated by third parties — those are the responsibility of the deployer.

## What Security Controls Are in Place

This repository enforces:

- Required reviews on all PRs (`CODEOWNERS`).
- Signed commits (GPG/SSH) required on `main` (verified by the branch-protection ruleset).
- Branch protection: no force-push, no direct push to `main`.
- Secret scanning (`gitleaks`) on every PR + on push + weekly full scan.
- Dependency scanning (Dependabot, Trivy).
- Static analysis (CodeQL for Kotlin and TypeScript).
- SBOM per release: every GitHub Release carries a cosign-signed CycloneDX SBOM plus SLSA/VEX/evidence bundle (Gradle `org.cyclonedx.bom` plugin; Trivy fallback for non-Gradle components), generated by the `release-evidence` job in `release-please.yml` and verifiable via `verify-release-evidence.yml`.
- Pre-commit hooks blocking common secret patterns.
- No `.env` files, private keys, certificates, or PII in repository (enforced by `.gitignore` + `.gitleaks.toml`).

### Maintainer Authentication Hardening (ADR-0279 #19)

The repository's strongest control is only as strong as the weakest maintainer login, so maintainer-facing authentication is held to a higher bar than the code:

- **Phishing-resistant MFA required.** Maintainers and org admins authenticate with WebAuthn/passkeys (hardware security keys) as the primary second factor. TOTP is an accepted fallback only during onboarding; SMS is never acceptable.
- **Two keys per maintainer.** Every maintainer registers a primary and a backup hardware key, so a lost key is a rotation event, not an account-recovery event. Recovery codes are stored offline.
- **Privileged actions re-authenticate.** Releases (release-please merge), ruleset changes, and Security Advisory publication are performed in a fresh, recently-authenticated session — a stolen long-lived cookie must not be enough to ship code to `main`.
- **Quarterly attestation.** The maintainer list, key registrations, and org admin roster are reviewed quarterly against HR/engagement records; departed maintainers lose access the same day (recorded in the review notes).
- **Signed commits are identity, not formality.** The GPG/SSH signing requirement above exists so that a compromised GitHub session alone cannot forge a maintainer's authorship; treat unsigned commits on `main` as an incident signal.

## Hardening Guidance for Deployers

If you are deploying OpenBank in any non-development environment, **you are responsible for**:

- Replacing all `CHANGE_ME_LOCAL_DEV_ONLY` default values with strong, rotated secrets stored in a real secrets manager (HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager).
- Performing your own threat model, penetration testing, and regulatory compliance review.
- Configuring TLS everywhere, not just at the gateway.
- Hardening Keycloak, Postgres, Vault, and Kafka per their respective production guides.
- Establishing incident response, audit logging retention, and key rotation policies.
- Obtaining all required banking, payment, and data-protection authorisations in your jurisdiction.

The OpenBank maintainers are **not responsible** for security incidents in production deployments operated by third parties.

