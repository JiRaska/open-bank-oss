# Security Policy

OpenBank is open-source banking platform software. While it is **not production-ready** and **not licensed to operate as a bank**, the code is designed around the defence-in-depth security practices expected of banking software. We take security reports seriously.

## Supported Versions

Only the `main` branch and the latest tagged release receive security fixes during the pre-alpha phase.

| Version | Supported |
|---------|-----------|
| `main`  | ✅        |
| latest tag | ✅     |
| anything else | ❌  |

## Reporting a Vulnerability

**DO NOT** open a public GitHub issue for security vulnerabilities.

### Channel

Report privately via:

**GitHub Security Advisories** (preferred):
https://github.com/JiRaska/open-bank-oss/security/advisories/new

GitHub Security Advisories provide a private collaboration space where maintainers and reporters can discuss, develop, and publish coordinated fixes.

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

> SLAs reflect the pre-alpha, community-maintained nature of the project. Once OpenBank reaches a production-ready release, SLAs will tighten significantly.

### Coordinated Disclosure

We follow [coordinated disclosure](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure): we will agree a public disclosure date with the reporter, typically after a fix is released or 90 days from the report, whichever is sooner.

Reporters are credited in release notes and the security advisory unless anonymity is requested.

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
- SBOM generation per release (Syft, CycloneDX format).
- Pre-commit hooks blocking common secret patterns.
- No `.env` files, private keys, certificates, or PII in repository (enforced by `.gitignore` + `.gitleaks.toml`).

## Hardening Guidance for Deployers

If you are deploying OpenBank in any non-development environment, **you are responsible for**:

- Replacing all `CHANGE_ME_LOCAL_DEV_ONLY` default values with strong, rotated secrets stored in a real secrets manager (HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager).
- Performing your own threat model, penetration testing, and regulatory compliance review.
- Configuring TLS everywhere, not just at the gateway.
- Hardening Keycloak, Postgres, Vault, and Kafka per their respective production guides.
- Establishing incident response, audit logging retention, and key rotation policies.
- Obtaining all required banking, payment, and data-protection authorisations in your jurisdiction.

The OpenBank maintainers are **not responsible** for security incidents in production deployments operated by third parties.

