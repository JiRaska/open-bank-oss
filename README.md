# OpenBank

> Cloud-native, open-source retail banking platform built on Kotlin + Quarkus, Next.js, and event-driven microservices — with governance, supply-chain security, and AI-agent operations baked in as code.

[![Services CI](https://github.com/JiRaska/open-bank-oss/actions/workflows/services-ci.yml/badge.svg)](https://github.com/JiRaska/open-bank-oss/actions/workflows/services-ci.yml)
[![CI](https://github.com/JiRaska/open-bank-oss/actions/workflows/ci.yml/badge.svg)](https://github.com/JiRaska/open-bank-oss/actions/workflows/ci.yml)
[![Security scan](https://github.com/JiRaska/open-bank-oss/actions/workflows/security.yml/badge.svg)](https://github.com/JiRaska/open-bank-oss/actions/workflows/security.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/JiRaska/open-bank-oss/badge)](https://scorecard.dev/viewer/?uri=github.com/JiRaska/open-bank-oss)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/13505/badge)](https://www.bestpractices.dev/projects/13505)
[![codecov](https://codecov.io/gh/JiRaska/open-bank-oss/graph/badge.svg)](https://codecov.io/gh/JiRaska/open-bank-oss)
[![Deploy drift](https://img.shields.io/github/issues-search/JiRaska/open-bank-oss?query=label%3Adeploy-drift%20state%3Aopen&label=deploy%20drift)](https://github.com/JiRaska/open-bank-oss/issues?q=label%3Adeploy-drift+state%3Aopen)
[![Open in GitHub Codespaces](https://img.shields.io/badge/Codespaces-Open-181717?logo=github)](https://codespaces.new/JiRaska/open-bank-oss)
[![Platform: Apache 2.0](https://img.shields.io/badge/Platform-Apache_2.0-brightgreen.svg)](https://opensource.org/licenses/Apache-2.0)
[![AI agents: AGPL-3.0 + commercial](https://img.shields.io/badge/AI_agents-AGPL--3.0--only_%2B_commercial-blue.svg)](docs/adr/0136-agent-services-agpl-in-repo-open-core.md)
[![Status: Beta](https://img.shields.io/badge/Status-Beta-blue.svg)](#project-status)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-blue.svg)](CONTRIBUTING.md)
[![Website](https://img.shields.io/badge/Website-open--bank.tech-1f6feb.svg)](https://open-bank.tech/)
[![Admin Portal](https://img.shields.io/badge/Admin_Portal-admin.open--bank.tech-1f6feb.svg)](https://admin.open-bank.tech/)

**🌐 [open-bank.tech](https://open-bank.tech/)** — project site & live sandbox · **🖥️ [admin.open-bank.tech](https://admin.open-bank.tech/)** — operator backoffice (Keycloak auth)

OpenBank is an **early-stage, community-driven** banking platform reference implementation. It demonstrates how a modern retail bank can be built with domain-driven design, hexagonal microservices, double-entry ledger accounting, PSD2 compliance, machine-enforced governance, and end-to-end observability.

> ⚠️ **This project is NOT production-ready and is NOT licensed to operate as a bank.** It is a software platform that someone with the appropriate banking licence and capital may deploy. Operating a real bank requires regulatory approval from your jurisdiction's central bank.

> ℹ️ **Trademark notice.** "OpenBank" is used here only as the name of this independent open-source project. It is **not affiliated with, endorsed by, or connected to** Santander's "Openbank", any other bank, or any trademark holder. See [TRADEMARKS.md](TRADEMARKS.md).

---

## Project Status

**Beta — M1 complete, M2/M3/M5 in progress.** ~37 backend microservices in the repo, all now deployed to the AWS sandbox — FINREP (previously the lone code-only service) deploys via its own ArgoCD Application (#547), and the new Verification-of-Payee (VoP) service is live (ADR-0171); customer-edge + admin-UI + developer portal deployed. The intra-bank money path is end-to-end; the ISO 20022 pipeline and clearing simulator are wired; live interbank network connections and multi-region are later milestones. See [docs/ROADMAP.md](docs/ROADMAP.md) for the full M1–M7 plan.

| Area | Status |
|---|---|
| Core domain (account, ledger, transaction, balance) | 🟢 Implemented, tested, deployed |
| Payments — intra-bank (transaction Temporal workflow → ledger → balance) | 🟢 End-to-end, deployed |
| Payments — interbank rails (SEPA, domestic, instant, clearing) | 🟡 ISO 20022 pipeline + clearing simulator wired (ADR-0104/0108); **live interbank network not connected** |
| PSD2 / Open Banking (consent, SCA, TPP registry) | 🟢 Consent + SCA + XS2A developer portal live (developer.open-bank.tech); TPP registry deployed to sandbox |
| EUDI / PID digital identity | 🟢 OpenID4VP + OpenID4VCI e2e live (ADR-0094); pid-service deployed |
| KYC / AML / Sanctions screening | 🟡 Real screening logic (pg_trgm), deployed; vendor feeds are stubs |
| GDPR Art. 17 right-to-erasure | 🟢 PARTY_ERASED event handled fleet-wide (kyc, notification, card-issuance) |
| Cards, disputes, interest, standing orders, statements, onboarding | 🟢 Implemented + deployed; standing-order daily scheduler live |
| Lending | 🟢 Deployed to sandbox (four-eyes KYC gate, ADR-0028); no live credit bureau integration |
| SWIFT messaging | 🟡 Deployed (ISO 20022 MT/MX pipeline wired); no live SWIFT network connection |
| Verification of Payee (VoP) | 🟢 Deployed to sandbox; name-match on outbound credit transfers, UI gate on SCT/SCT Inst (ADR-0171) |
| AnaCredit, SDD, TPP registry | 🟢 Implemented, deployed to sandbox |
| FINREP | 🟢 Implemented, deployed to sandbox (ArgoCD Application registered, #547) |
| Product catalog | 🟢 Implemented, deployed |
| Customer edge (BFF) + mobile app | 🟡 BFF deployed (OPA enforce mode on); KMP/Compose app in active dev (separate repo) |
| AI agent service (MCP, policy-gated) | 🟡 Fleet read tools + audit (ADR-0031); **copilot-service with LLM deployed in sandbox** |
| Fraud detection | 🟡 Deployed; Phase 2 velocity-counter signal plane wired (ADR-0084); rule engine pending |
| Durable workflow execution | 🟢 Temporal live (ADR-0101); FX + statement flows on durable execution |
| Test coverage | 🟡 Ratchet-only gate; money-path services carry higher floors |
| Governance-as-code (versioning, release, catalog) | 🟢 CI-enforced (ADR-0029) |
| Supply-chain security (SBOM, signing, SAST, pen-test P0–P2) | 🟢 CI-enforced (ADR-0030); external pen-test P0–P2 findings remediated |
| CI/CD | 🟢 Self-hosted runners + path-scoped gates + GitOps auto-deploy (ADR-0040) |
| Observability (OTel, Grafana, Prometheus, Loki, Tempo, Pyroscope) | 🟢 Live; GoAlert on-call, Pyrra SLO-as-code, GlitchTip errors, DomainMetrics fleet-wide |
| Cloud substrate (AWS, OpenTofu, ArgoCD GitOps) | 🟢 Sandbox live (EKS + ArgoCD) — full in-repo backend fleet deployed (no service code-only any longer) |

### What works right now (sandbox at open-bank.tech)

| Feature | How to try it | Notes |
|---|---|---|
| **Create account** | `POST https://api.open-bank.tech/api/v1/accounts` | Returns IBAN (Czech mod-11 BBAN) |
| **Get balance** | `GET https://api.open-bank.tech/api/v1/balances/{accountId}` | Multi-currency pockets |
| **SEPA payment** | `POST https://api.open-bank.tech/api/v1/sepa-payments` | Sanctions/AML gate, Temporal workflow → ledger |
| **Domestic payment** | `POST https://api.open-bank.tech/api/v1/domestic-payments` | CERTIS-style Czech domestic |
| **SEPA Instant** | `POST https://api.open-bank.tech/api/v1/sepa-instant-payments` | 10s settlement window |
| **Standing orders** | `POST https://api.open-bank.tech/api/v1/standing-orders` | Daily due-date sweep, outbox-backed |
| **Admin UI** | https://admin.open-bank.tech | Operator backoffice (Keycloak auth) |
| **Developer portal** | https://developer.open-bank.tech | PSD2 XS2A API explorer, TPP sandbox (WAF/ModSecurity) |
| **AI copilot** | `POST https://copilot.open-bank.tech/api/v1/copilot` | Customer-facing LLM — bearer-auth + rate-limited at the ingress, sandbox only |
| **EUDI identity** | `GET https://api.open-bank.tech/api/v1/pid` | OpenID4VP + OpenID4VCI credential flows live |
| **AI agent (ops)** | MCP endpoint (see agent-service docs) | Policy-gated, read-only fleet tools |

All API calls require a Bearer token from `https://kc.open-bank.tech/realms/openbank`. See [docs/QUICKSTART_SANDBOX.md](docs/QUICKSTART_SANDBOX.md) for a `curl` walkthrough.

> ⚠️ The public sandbox is a **best-effort demo, not a service.** Every endpoint is auth-gated (Keycloak bearer) and rate-limited at the ingress, but it may be reset, throttled, or taken offline at any time, carries **no SLA**, and must **never** receive real personal or payment data.

### What is NOT there yet

See the full list in [docs/ROADMAP.md — Known gaps](docs/ROADMAP.md#known-gaps-honest-list).

---

## Quick Start (Local Docker)

**Prerequisites:** Docker Desktop ≥ 4.x (Compose v2), 16 GB RAM recommended (the full fleet is ~40 services).

```bash
cd openbank-infra

# 1. Copy environment template and adjust secrets for local development
cp .env.example .env
$EDITOR .env

# 2. Start infrastructure (Postgres, Kafka, Apicurio, Keycloak, OpenBao, Valkey, OPA, observability)
make up-infra

# 3. Build and start all application services + Admin UI
make up-all

# 4. Verify health
make health-all
```

Key endpoints:

| Service | URL |
|---|---|
| Admin UI | http://localhost:3000 |
| Account Service API | http://localhost:8100 |
| Ledger Service API | http://localhost:8101 |
| Transaction Service API | http://localhost:8102 |
| Keycloak | http://localhost:8080 |
| Apicurio Schema Registry | http://localhost:8081 |
| Kafka UI | http://localhost:8090 |
| OPA (policy decision point) | http://localhost:8181 |
| Grafana | http://localhost:3001 |
| Prometheus | http://localhost:9090 |
| OpenBao | http://localhost:8200 |

Credentials for local dev are read from your `.env` file — see [`openbank-infra/.env.example`](openbank-infra/.env.example).

---

## Architecture

OpenBank follows **hexagonal architecture** per service (ADR-0002) with an event-driven backbone
(Apache Kafka + transactional outbox, ADR-0003), OPA-enforced authorization at every decision point
(ADR-0034), and machine-enforced governance as code (ADR-0029). Money-path services require two
human approvals and a threat model (ADR-0030). The API contract version is independent of the
service release version (ADR-0048).

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full C4-style narrative — context
diagram, container diagram, key decisions, deployment topology, and security architecture.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Kotlin 2.3.20 + Quarkus 3.37 |
| Database | PostgreSQL 18 via CNPG (Flyway migrations) — cluster fleet migrated 16 → 18 (runbook 0003); local Docker dev still on 16 |
| Messaging | Apache Kafka, cluster operated by the Strimzi operator (transactional outbox); Redpanda for per-JVM integration tests |
| Schema registry | Apicurio 2.6 |
| Cache | Valkey 7.2 |
| IAM | Keycloak 24 |
| Secrets | OpenBao (LF fork of Vault; runbook 0005), synced into the cluster by External Secrets Operator |
| Policy / authz | Open Policy Agent (OPA) for app-level authz; Kyverno for cluster admission policy + supply-chain (SBOM/signature) verification |
| Durable execution | Temporal (ADR-0101/ADR-0120) — payment orchestration (transaction, SEPA, domestic) plus FX + statement flows; superseded the earlier custom saga framework (ADR-0045, now superseded) |
| Observability | OpenTelemetry → Grafana (Prometheus + Loki + Tempo + Pyroscope); GoAlert on-call, Pyrra SLO-as-code, GlitchTip errors, ntfy push channel |
| Admin UI | Next.js 16 + React 19 + TypeScript + shadcn/ui |
| Ingress | ingress-nginx is the sandbox's actual entrypoint (per-service Ingress + rate limiting); Kong OSS ([`openbank-api-gateway`](openbank-api-gateway/README.md)) is a local-Docker-only dev gateway, not deployed to the sandbox |
| Autoscaling | Karpenter (node autoscaling, Spot/Graviton); KEDA (event-driven pod autoscaling, scale-to-zero) |
| Networking / TLS | cert-manager (TLS lifecycle); external-dns (DNS sync); NetworkPolicies fleet-wide, default-deny (generated by `gen-network-policies.py`). Istio service mesh is designed (`openbank-infra/k8s/base/istio.yaml`) but not wired into the sandbox — mTLS isolation today is NetworkPolicy-based, not mesh-based |
| Runtime security | Falco |
| Cloud substrate | Cloud-agnostic on Kubernetes; AWS sandbox via OpenTofu ([`openbank-infra/aws`](openbank-infra/aws/README.md)) |
| GitOps / delivery | ArgoCD (ADR-0010) + Argo Rollouts canary progressive delivery (ADR-0098) |
| AI agents | MCP, policy-gated via OPA; hybrid/model-agnostic LLM gateway (ADR-0031) |
| Customer app | Kotlin Multiplatform + Compose Multiplatform — in active dev, separate repo (ADR-0064) |

---

## Build

```bash
./gradlew :<module>:build                          # one service, e.g. :openbank-ledger-service:build
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```

CI is path-scoped — only changed services build (ADR-0040). Before opening a PR, verify the same gates
CI enforces (ADR-0029): PR (no direct `main` commits), per-service version bump, Conventional-Commit message,
`openapi.yaml` + contract test for API changes, Flyway migration for DB changes, tests for new behavior,
and a threat model for money-path services (ADR-0030). See [CONTRIBUTING.md](CONTRIBUTING.md) for the
full checklist. Maintainers with Claude Code can also run `/ship-check` — it mirrors the same gates.

---

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how the services fit together (bounded contexts, runtime patterns, deployment)
- [`DEPLOYMENT.md`](DEPLOYMENT.md) — how it's built, shipped, and run (local Docker, CI/CD, GitOps, infra, runbooks)
- [`docs/deployment-reference.md`](docs/deployment-reference.md) — evaluator's reference: topology, sizing tiers, cost estimates, and the production delta
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — milestones M1–M7
- [`docs/adr/README.md`](docs/adr/README.md) — Architecture Decision Records index, with per-decision delivery status (governance lives in 0029–0031 and 0040)
- [`docs/strategy/`](docs/strategy/) — BIAN mapping, security baseline, compliance matrix, resilience
- [`RELEASE_NOTES.md`](RELEASE_NOTES.md) — per-component changelogs (release-please, Conventional Commits)
- [`CLAUDE.md`](CLAUDE.md) — agent & contributor guide (human summary of `rules.yaml`)
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to contribute
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — community standards
- [`SECURITY.md`](SECURITY.md) — vulnerability disclosure

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

New here? The fastest path in: pick a [`good-first-issue`](https://github.com/JiRaska/open-bank-oss/labels/good-first-issue)
(each carries a newcomer-context comment telling you exactly where to start), spin the stack up with
one command (`cd openbank-infra && make up-infra && make up-all`), and sanity-check the live sandbox
via [`docs/QUICKSTART_SANDBOX.md`](docs/QUICKSTART_SANDBOX.md). Target: first green PR in ~15 minutes
of hands-on time.

OpenBank uses the [Developer Certificate of Origin](https://developercertificate.org/) — every commit must
be signed off and signed with `git commit -s -S`.

---

## Security

If you discover a security vulnerability, **do not open a public issue**. Please report it through GitHub
Security Advisories or by emailing the maintainers — see [SECURITY.md](SECURITY.md) for details.

---

## Regulatory Compliance References

The platform is designed with the following regulatory frameworks in mind. None of this is legal advice.
**Deploying OpenBank as a real bank requires your own licensing, compliance, and legal review.**

- **CNB** — Czech National Bank, Act No. 21/1992 Coll. (Banking Act)
- **EBA** — PSD2, 5AMLD, DORA (in effect since 17 Jan 2025)
- **PCI DSS** — v4.0 (payment card industry)
- **GDPR** — Regulation (EU) 2016/679 (data protection)

Auditor-facing evidence and control mappings live under [`docs/compliance/`](docs/compliance/):
the [DORA + supply-chain evidence pack](docs/compliance/evidence-pack.md) and the
[FINOS CCC + AIGF control mapping](docs/compliance/finos-ccc-mapping.md).

---

## License

OpenBank uses a **dual-license model** (ADR-0123, superseding ADR-0012):

**The platform (this repository) — [Apache License 2.0](LICENSE) + DCO.**

- ✅ Free to use, modify, distribute (including commercially)
- ✅ Patent grant included
- ✅ Permissive — no copyleft; forks and downstream may relicense their changes
- ✅ You may combine OpenBank with proprietary code

Every platform source file carries an SPDX `Apache-2.0` header; the AI agent and agent-plane services
carry `AGPL-3.0-only` (see below). Contributions are certified via the
[Developer Certificate of Origin](https://developercertificate.org/) — no CLA.

**The AI agent / agent-plane services — AGPL-3.0-only + a parallel commercial licence (open-core).**

⚠️ **This repository is not entirely Apache-2.0.** Per
[ADR-0136](docs/adr/0136-agent-services-agpl-in-repo-open-core.md) (superseding the ADR-0031 D8
separate-repo plan, and extended by [ADR-0181](docs/adr/0181-mcp-server-exposing-psd2-and-admin-read-apis-to-governed-ai-agents.md)
and [ADR-0193](docs/adr/0193-ap2-mandate-verification-model-and-liability-position-promotes-adr-0182.md),
which place two further agent-plane services inside the same boundary), the part of OpenBank intended
for commercialization — the agent-plane
services — is licensed **AGPL-3.0-only in this repo**, with a **commercial licence available from the
maintainer** as an alternative (open-core dual-licensing). If you redistribute, modify or operate one
of those modules — including offering it to users over a network — the AGPL-3.0 applies.

**Which modules?** The authoritative list is the `agpl_modules` key in
[`openbank-libs/governance/rules.yaml`](openbank-libs/governance/rules.yaml)
(`dependencies.license_boundary_exceptions`). It is intentionally not repeated here — a second
hand-maintained copy is how this section came to name four modules while the tree contained twelve.
Equivalently, and checkably: **a module is AGPL-3.0-only iff it contains its own `LICENSE` file**, and
every file in it carries `SPDX-License-Identifier: AGPL-3.0-only`. The full licence text is in
[`LICENSES/AGPL-3.0-only.txt`](LICENSES/AGPL-3.0-only.txt). The one documented exception is
already-applied Flyway migrations, whose headers are frozen by Flyway's checksum and are therefore
corrected out-of-tree in [`REUSE.toml`](REUSE.toml). That every one of these declarations agrees is
enforced on each PR by [`check-license-headers.py`](.github/scripts/check-license-headers.py).

The AGPL **does not contaminate the Apache-2.0 platform**: no Apache module takes a build/compile
dependency on an AGPL module (they are reached only over HTTP, which the AGPL treats as use rather than
linking), and the AGPL modules depend only on the Apache-2.0 `openbank-libs` (copyleft may consume
permissive code). `rules.yaml` records this boundary and the gate above enforces it.

See [`LICENSE`](LICENSE) for full Apache-2.0 text and [ADR-0123](docs/adr/0123-relicense-to-apache-2.0.md) for the
relicensing rationale (and [ADR-0012](docs/adr/0012-mpl-license-and-dco.md) for the original MPL decision it supersedes).

---

## Acknowledgements

OpenBank stands on the shoulders of giants:

- [Apache Fineract](https://github.com/apache/fineract) — pioneering open-source core banking
- [Apache Mifos](https://mifos.org/) — community-driven banking platform
- [Open Bank Project](https://www.openbankproject.com/) — open banking API standard
- All maintainers of Kotlin, Quarkus, Next.js, Kafka, PostgreSQL, Keycloak, OpenBao, and OPA

---

**Maintainer:** [@JiRaska](https://github.com/JiRaska)
**Repository:** https://github.com/JiRaska/open-bank-oss
