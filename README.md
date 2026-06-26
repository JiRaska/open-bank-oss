# OpenBank

> Cloud-native, open-source retail banking platform built on Kotlin + Quarkus, Next.js, and event-driven microservices — with governance, supply-chain security, and AI-agent operations baked in as code.

[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](https://opensource.org/licenses/MPL-2.0)
[![Agent runtime: AGPL 3.0 + commercial](https://img.shields.io/badge/Agent_runtime-AGPL_3.0_%2B_commercial-blue.svg)](docs/adr/0012-mpl-license-and-dco.md)
[![Status: Alpha](https://img.shields.io/badge/Status-Alpha-orange.svg)](#project-status)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-blue.svg)](CONTRIBUTING.md)

OpenBank is an **early-stage, community-driven** banking platform reference implementation. It demonstrates how a modern retail bank can be built with domain-driven design, hexagonal microservices, double-entry ledger accounting, PSD2 compliance, machine-enforced governance, and end-to-end observability.

> ⚠️ **This project is NOT production-ready and is NOT licensed to operate as a bank.** It is a software platform that someone with the appropriate banking licence and capital may deploy. Operating a real bank requires regulatory approval from your jurisdiction's central bank.

---

## Project Status

**Alpha — Foundation hardening (Milestone M1).** 27 services run in the AWS sandbox with domain logic, REST APIs, tests, CI gates, GitOps auto-deploy, and an observability stack with on-call and SLO-as-code. The intra-bank money path is end-to-end; interbank settlement rails, compliance evidence, and multi-region are later milestones. See [docs/ROADMAP.md](docs/ROADMAP.md) for the full M1–M7 plan.

| Area | Status |
|---|---|
| Core domain (account, ledger, transaction, balance) | 🟢 Implemented, tested, deployed |
| Payments — intra-bank (transaction saga → ledger → balance) | 🟢 End-to-end, deployed |
| Payments — interbank rails (SEPA, domestic, instant, SWIFT, clearing) | 🟡 Accept + screen + persist + emit event; **no ISO 20022 / settlement wiring yet** |
| PSD2 / Open Banking (consent, SCA, TPP registry) | 🟡 Consent + SCA deployed; PSD2 XS2A + TPP registry code-only |
| KYC / AML / Sanctions screening | 🟡 Real screening logic (pg_trgm), deployed; vendor feeds are stubs |
| Cards, disputes, interest, standing orders, statements, onboarding | 🟢 Implemented + deployed in sandbox |
| Lending, AnaCredit, SDD, SWIFT, PID identity | 🟡 Implemented, code-only (not deployed) |
| Product catalog | 🟢 Implemented, deployed |
| Customer edge (BFF) + mobile app | 🟡 BFF deployed; KMP/Compose app in active dev (separate repo) |
| AI agent service (MCP, policy-gated) | 🟡 Kill-switch + prompt-injection guardrail + read tools (ADR-0031); no production LLM yet |
| Fraud service | 🔴 Scaffold only (ADR-0084) — built, not yet deployed |
| Test coverage | 🟡 Ratchet-only gate; money-path services carry higher floors |
| Governance-as-code (versioning, release, catalog) | 🟢 CI-enforced (ADR-0029) |
| Supply-chain security (SBOM, signing, SAST, gitleaks, image scan) | 🟢 CI-enforced (ADR-0030) |
| CI/CD | 🟢 Self-hosted runners + path-scoped gates + GitOps auto-deploy (ADR-0040) |
| Observability (OTel, Grafana, Prometheus, Loki, Tempo, Pyroscope) | 🟢 Live; + GoAlert on-call, Pyrra SLO-as-code, GlitchTip errors (ADR-0088) |
| Cloud substrate (AWS, OpenTofu, ArgoCD GitOps) | 🟢 Sandbox live (EKS + ArgoCD), 27 services deployed |

### What works right now (sandbox at open-bank.tech)

| Feature | How to try it | Notes |
|---|---|---|
| **Create account** | `POST https://api.open-bank.tech/api/v1/accounts` | Returns IBAN (Czech mod-11 BBAN) |
| **Get balance** | `GET https://api.open-bank.tech/api/v1/balances/{accountId}` | Multi-currency pockets |
| **SEPA payment** | `POST https://api.open-bank.tech/api/v1/sepa-payments` | Sanctions/AML gate, saga → ledger |
| **Domestic payment** | `POST https://api.open-bank.tech/api/v1/domestic-payments` | CERTIS-style Czech domestic |
| **SEPA Instant** | `POST https://api.open-bank.tech/api/v1/sepa-instant-payments` | 10s settlement window |
| **Admin UI** | https://admin.open-bank.tech | Operator backoffice (Keycloak auth) |
| **AI agent** | MCP endpoint (see agent-service docs) | Policy-gated, read-only fleet tools |

All API calls require a Bearer token from `https://kc.open-bank.tech/realms/openbank`. See [docs/QUICKSTART_SANDBOX.md](docs/QUICKSTART_SANDBOX.md) for a `curl` walkthrough.

### What is NOT there yet (honest list)

- **Interbank rails do not settle.** Money only moves end-to-end for intra-bank transfers (transaction saga → ledger → balance). SEPA / domestic / instant / SWIFT / clearing accept, screen, persist and **emit an event**, but build no ISO 20022 / MT message and post nothing to the ledger — the settlement leg is not wired yet.
- **Customer app is not GA.** A Kotlin Multiplatform + Compose customer app and its `openbank-customer-edge` BFF exist and are in active development in a **separate repo** (`JiRaska/openbank-app`); the BFF is deployed but the app is not released.
- **KYC/AML vendors are stubs** — screening logic is real (sanctions uses pg_trgm fuzzy matching) but runs against in-memory/seed lists, not real providers (Refinitiv, ComplyAdvantage, EBA feed).
- **Some services are code-only, not deployed** — lending, anacredit, sdd, swift, pid (identity resolution), psd2 and tpp-registry are implemented but not yet wired into the sandbox cluster.
- **SCA is maturing, not complete** — passkey RP, settlement gate and non-repudiation hash chain are in (ADR-0086), but full FIDO2 attestation / real OTP delivery are not finished.
- **AI agent has no production LLM** — kill-switch, prompt-injection guardrail and read-only fleet tools are in; the model gateway is deferred (sandbox/free model only).
- **Contract tests are thin** — one published pact across the fleet; the contract-test gate exists but coverage is a known gap.
- **No pen test, no DR test, no HA** — single-node sandbox, no failover; most Postgres clusters still lack S3 backups — only 2 are configured (blocks the PG 18 upgrade).
- **Not licensed to operate as a bank.** See the disclaimer above.

---

## Quick Start (Local Docker)

**Prerequisites:** Docker Desktop ≥ 4.x (Compose v2), 16 GB RAM recommended (the full fleet is ~30 services).

```bash
cd openbank-infra

# 1. Copy environment template and adjust secrets for local development
cp .env.example .env
$EDITOR .env

# 2. Start infrastructure (Postgres, Kafka, Apicurio, Keycloak, Vault, Valkey, OPA, observability)
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
| Vault | http://localhost:8200 |

Credentials for local dev are read from your `.env` file — see [`openbank-infra/.env.example`](openbank-infra/.env.example).

---

## Architecture

### Hexagonal architecture per service (ADR-0002)

```
domain/          — Pure Kotlin, zero framework dependencies
  model/         — Entities, value objects
  event/         — Domain events

application/
  port/in/       — Use case interfaces (commands, queries)
  port/out/      — Repository + event publisher interfaces
  usecase/       — Use case implementations

infrastructure/
  persistence/   — JPA entities, Panache repositories
  messaging/     — Kafka publishers (transactional outbox, ADR-0003)
  rest/          — REST resources, DTOs, exception mappers
```

The **domain layer has zero framework imports** — enforced by CI. Shared runtime plumbing (Money, IBAN,
idempotency, audit, outbox, service-info, API-version filter, authz) lives in `openbank-libs`.

### Service catalogue

| Service | Port | Description |
|---|---|---|
| `openbank-account-service` | 8100 | Account lifecycle (open, freeze, close) |
| `openbank-ledger-service` | 8101 | Double-entry general ledger |
| `openbank-transaction-service` | 8102 | Transaction posting, idempotency |
| `openbank-balance-service` | 8103 | Real-time balance projection |
| `openbank-product-catalog` | 8104 | Banking product catalog |
| `openbank-pid-service` | 8105 | Party identity resolution & dedup (ADR-0072) |
| `openbank-consent-service` | 8106 | PSD2 consent management |
| `openbank-psd2-service` | 8107 | PSD2 AISP/PISP API |
| `openbank-tpp-registry-service` | 8108 | Third-party provider registry |
| `openbank-agent-service` | 8109 | AI agent integration (MCP, policy-gated) |
| `openbank-sca-service` | 8110 | Strong Customer Authentication |
| `openbank-party-service` | 8111 | Customer master data |
| `openbank-notification-service` | 8112 | Customer notifications |
| `openbank-audit-service` | 8113 | Audit trail aggregation |
| `openbank-kyc-service` | 8114 | Know-Your-Customer onboarding |
| `openbank-sepa-payment` | 8115 | SEPA Credit Transfer |
| `openbank-domestic-payment` | 8116 | Domestic payment processing |
| `openbank-aml-service` | 8117 | Anti-money-laundering screening |
| `openbank-card-issuance-service` | 8118 | Card issuance |
| `openbank-fx-service` | 8119 | Foreign exchange |
| `openbank-security-scanner` | 8120 | Internal security scanning |
| `openbank-standing-order-service` | 8121 | Recurring payments |
| `openbank-swift-service` | 8122 | SWIFT MT/MX messaging |
| `openbank-clearing-service` | 8124 | Clearing & settlement |
| `openbank-interest-service` | 8125 | Interest calculation & accrual |
| `openbank-dispute-service` | 8135 | Card disputes & chargebacks |
| `openbank-sepa-instant` | 8127 | SEPA Instant Credit Transfer |
| `openbank-sanctions-service` | — | Sanctions list screening (pg_trgm fuzzy match) |
| `openbank-onboarding-service` | — | Onboarding funnel projection (party/KYC/SCA) |
| `openbank-statement-service` | — | Account statements (camt.053 / MT940 / PDF) |
| `openbank-sdd-service` | — | SEPA Direct Debit mandates (debtor side) |
| `openbank-lending-service` | — | Loan origination & servicing (four-eyes) |
| `openbank-anacredit-service` | — | AnaCredit regulatory report builder |
| `openbank-analytics-sink` | — | Event analytics sink |
| `openbank-customer-edge` | — | Customer BFF for the mobile app (ownership/IDOR guards) |
| `openbank-fraud-service` | — | Fraud detection (scaffold, ADR-0084) |
| `openbank-api-gateway` | — | Kong gateway configuration |
| `openbank-admin-ui` | 3000 | Bank operator console (Next.js) |
| `openbank-libs` | — | Shared primitives + runtime plumbing |

> `openbank-fraud-service` is an **early scaffold** (ADR-0084) — built by Gradle but not yet deployed.
> `openbank-analytics-sink` is implemented but not a released component (no `version.txt`).
> Deployed-to-sandbox vs code-only status is tracked in the [Project Status](#project-status) table above.

### Governance, security & operations as code

OpenBank treats non-functional concerns as machine-enforced, not as prose:

- **Governance-as-code (ADR-0029):** per-service SemVer, release-please changelogs, and a CI-derived
  service catalog. Rules live in [`openbank-libs/governance/rules.yaml`](openbank-libs/governance/rules.yaml)
  — the single source of truth that **both** CI gates and the agent guide ([`CLAUDE.md`](CLAUDE.md)) read.
- **Supply-chain & SSDLC (ADR-0030):** SBOM, container signing, SAST, dependency/CVE scanning, gitleaks,
  OpenAPI lint — gated in CI.
- **Fine-grained authz (ADR-0018):** Open Policy Agent runs as the policy decision point (port 8181)
  for service authorization; a unified Kotlin enforcement API across REST and MCP is on the roadmap.
- **AI-agent governance (ADR-0031):** policy-gated MCP tools, human-in-the-loop gates, and AI-attributed
  audit — charters in [`openbank-libs/governance/agents.yaml`](openbank-libs/governance/agents.yaml).

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Kotlin 2.0 + Quarkus 3.x |
| Database | PostgreSQL 16 (Flyway migrations); 18 upgrade in progress (CNPG, blocked on S3 backups) |
| Messaging | Apache Kafka 3.7 + transactional outbox |
| Schema registry | Apicurio 2.6 |
| Cache | Valkey 7.x |
| IAM | Keycloak 24 |
| Secrets | HashiCorp Vault |
| Policy / authz | Open Policy Agent (OPA) |
| Observability | OpenTelemetry → Grafana (Prometheus + Loki + Tempo + Pyroscope); GoAlert on-call, Pyrra SLO-as-code, GlitchTip errors |
| Admin UI | Next.js 16 + React 19 + TypeScript + shadcn/ui |
| API Gateway | Kong |
| Cloud substrate | Cloud-agnostic on Kubernetes; AWS sandbox via OpenTofu ([`openbank-infra/aws`](openbank-infra/aws/README.md)) |
| GitOps | ArgoCD (ADR-0010) |
| AI agents | MCP, policy-gated via OPA; hybrid/model-agnostic LLM gateway (ADR-0031) |
| Customer app | Kotlin Multiplatform + Compose Multiplatform — in active dev, separate repo (ADR-0064) |
| Service Mesh | Istio (planned) |

---

## Build

```bash
./gradlew :<module>:build                          # one service, e.g. :openbank-ledger-service:build
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```

CI is path-scoped — only changed services build (ADR-0040). Before opening a PR, run the ship-checklist
(`/ship-check`), which mirrors the exact CI gates: PR (no direct `main` commits), per-service version bump,
Conventional-Commit message, `openapi.yaml` + contract test for API changes, Flyway migration for DB changes,
tests for new behavior, and a threat model for money-path services (ADR-0030).

---

## Documentation

- [`docs/ROADMAP.md`](docs/ROADMAP.md) — milestones M1–M7
- [`docs/adr/`](docs/adr/) — Architecture Decision Records (governance lives in 0029–0031 and 0040)
- [`docs/strategy/`](docs/strategy/) — BIAN mapping, security baseline, compliance matrix, resilience
- [`CLAUDE.md`](CLAUDE.md) — agent & contributor guide (human summary of `rules.yaml`)
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to contribute
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — community standards
- [`SECURITY.md`](SECURITY.md) — vulnerability disclosure

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

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

---

## License

OpenBank uses a **dual-license model** (ADR-0012):

**The platform (this repository) — [Mozilla Public License 2.0](LICENSE) + DCO.**

- ✅ Free to use, modify, distribute (including commercially)
- ✅ Patent grant included
- ⚠️ Modifications to MPL-licensed files must be released under MPL
- ✅ You may combine OpenBank with proprietary code in new files

Every source file carries an SPDX header (`// SPDX-License-Identifier: MPL-2.0`), and contributions are
certified via the [Developer Certificate of Origin](https://developercertificate.org/) — no CLA.

**The AI agent runtime component — AGPL-3.0 + a parallel commercial licence (open-core).**

Per the ADR-0012 amendment (ADR-0031 D8), the part of OpenBank intended for commercialization — the AI
agent **runtime** — is dual-licensed AGPL-3.0 / commercial and lives in a **separate repository/module with
its own LICENSE and a CLA** (the CLA is what makes dual-licensing possible). This does **not** apply to any
MPL-2.0 code in this repo; the policy/charter config here (`openbank-agent-service`, `governance/agents.yaml`)
stays MPL-2.0 + DCO. `rules.yaml` carries a documented carve-out so the governance gate and reality agree.

See [`LICENSE`](LICENSE) for full MPL text and [ADR-0012](docs/adr/0012-mpl-license-and-dco.md) for the
dual-license rationale.

---

## Acknowledgements

OpenBank stands on the shoulders of giants:

- [Apache Fineract](https://github.com/apache/fineract) — pioneering open-source core banking
- [Apache Mifos](https://mifos.org/) — community-driven banking platform
- [Open Bank Project](https://www.openbankproject.com/) — open banking API standard
- All maintainers of Kotlin, Quarkus, Next.js, Kafka, PostgreSQL, Keycloak, Vault, and OPA

---

**Maintainer:** [@JiRaska](https://github.com/JiRaska)
**Repository:** https://github.com/JiRaska/open-bank
