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

**Beta reference implementation.** The repository contains banking services, an operator
console, shared libraries, infrastructure, and governed AI services. Components release
independently; a component version or a passing CI badge is not a platform-wide readiness claim.

Source review: **2026-10-03**. This overview describes checked-in capabilities. Deployment
health, provider connectivity, and control effectiveness require current runtime evidence.
See the [roadmap](docs/ROADMAP.md) for remaining acceptance work and the
[ADR registry](docs/adr/CURRENT.md) for decision and delivery status.

| Area | What is in the repository | Evaluation boundary |
|---|---|---|
| Core banking | Accounts, double-entry ledger, balances, transaction orchestration, interest and standing orders | Validate the relevant money path and reconciliation against your configuration |
| Payments and treasury | SEPA, instant, domestic, SDD, SWIFT, clearing, settlement, VoP and treasury components | Simulators and provider adapters do not establish live scheme access |
| Identity and controls | Party, KYC/KYB, consent, SCA, PSD2, PID, AML, sanctions and fraud components | Credentialed providers, conformance and operator acceptance are separate gates |
| Products and servicing | Product catalogs, lending, cards, disputes, statements, documents and notifications | Capability availability varies by product, adapter and feature flag |
| Operator experience | Next.js admin UI, approval flows, evidence views and component documentation | Access and actions depend on roles, policy and deployment configuration |
| AI and investigations | Copilot, MCP, context graph and specialist agents | Governed capabilities and rollout states; see [agent registry](openbank-libs/governance/agents.yaml) |
| Engineering evidence | Contract tests, fuzzing, synthetic journeys, performance checks, supply-chain and governance gates | Each check proves its declared scope; the roadmap does not equate configuration with a passing run |

Component source and tests live under `openbank-*`. The
[release configuration](release-please-config.json) identifies released components;
[repository layout](docs/repository-layout.md) explains libraries, tools and infrastructure
that are not interchangeable with deployed services.

### Explore the sandbox

Start with the [sandbox guide](docs/QUICKSTART_SANDBOX.md) and request an appropriate
identity through [support](SUPPORT.md). The [admin console](https://admin.open-bank.tech/)
is the main operator entry point. API paths and request schemas are defined by each
service's `src/main/resources/openapi.yaml`.

The sandbox is a best-effort demonstration with no SLA. Availability and data may change;
use synthetic data only. This documentation refresh did not revalidate live endpoints.

### What is NOT there yet

See [known gaps](docs/ROADMAP.md#known-gaps-honest-list): production acceptance, external
scheme/provider qualification, independent assurance, and complete multi-region recovery
and capacity evidence remain separate from feature implementation.

---

## Quick Start (Local Docker)

For one service, start with the [contributor setup](CONTRIBUTING.md#local-development-setup):
JDK 25, the bundled Gradle wrapper, and a running Docker engine for Testcontainers.
The Compose configuration is a development environment, not a replica of every GitOps component.

```bash
cd openbank-infra
cp .env.example .env
$EDITOR .env

# Validate configuration and inspect the services available in this checkout.
docker compose config --quiet
docker compose config --services

# Start the local identity, database, messaging and cache dependencies.
docker compose up -d --wait postgres kafka schema-registry valkey openbao keycloak

# Build and start the chosen service and its declared dependencies.
docker compose up -d --build --wait account-service
docker compose ps
```

Use the checked-in [Compose file](openbank-infra/docker-compose.yml) for service names,
ports, health checks and dependencies. Add the services needed for the flow you are testing;
starting one API does not start an entire banking journey. Local account HTTP is on `:8100`,
Keycloak on `:8080`; the optional admin UI is on `:3000`.

The legacy Makefile helpers contain an outdated `vault` service reference and health checks
for only a subset of services; the direct Compose commands above avoid that mismatch.
These commands were checked against the configuration, not run as a full Docker deployment
in this documentation review. Resource requirements depend on the selected services.

---

## Architecture

OpenBank follows **hexagonal architecture** per service (ADR-0002) with an event-driven backbone
(Apache Kafka + transactional outbox, ADR-0003), OPA-enforced authorization at every decision point
(ADR-0034), and machine-enforced governance as code (ADR-0029). Money-path services require two
human approvals and a threat model (ADR-0030). The API contract version is independent of the
service release version (ADR-0048).

See the [architecture hub](ARCHITECTURE.md) for the overview and contributor guide, plus
[docs/repository-layout.md](docs/repository-layout.md) for a map of the monorepo.

---

## Tech Stack

| Layer | Technology | Version/configuration source |
|---|---|---|
| Backend | Kotlin, Quarkus, JDK 25 | [Version catalogue](openbank-libs/gradle/libs.versions.toml), [service convention](build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts) |
| Build | Gradle wrapper and shared convention plugins | [Wrapper](gradle/wrapper/gradle-wrapper.properties), [build-logic](build-logic/) |
| Operator UI | Next.js, React, TypeScript | [Package manifest](openbank-admin-ui/package.json) and lockfile |
| Data and messaging | PostgreSQL, Kafka, Apicurio, Valkey | [Local Compose](openbank-infra/docker-compose.yml), [GitOps components](openbank-infra/gitops/components/) |
| Identity, secrets and policy | Keycloak, OpenBao, OPA, Kyverno | [GitOps configuration](openbank-infra/gitops/) |
| Durable execution | Temporal and transactional outbox | [Temporal library](openbank-libs-temporal/), [runtime library](openbank-libs-runtime/) |
| Observability | OpenTelemetry, Prometheus, Grafana, Loki, Tempo, Pyroscope | [Observability configuration](openbank-infra/gitops/components/observability/) |
| Delivery | OpenTofu, Kubernetes, ArgoCD and GitHub Actions | [Infrastructure](openbank-infra/), [workflows](.github/workflows/) |
| Customer app | Kotlin Multiplatform / Compose; separate project | [Customer application](https://github.com/JiRaska/openbank-app) |

Exact dependency versions belong in their manifests, not a second manually maintained
inventory. Local Compose and GitOps may pin different versions. NetworkPolicies control
network access; they do not provide transport encryption. Consult the
[architecture overview](docs/ARCHITECTURE.md) for ingress and TLS boundaries.

---

## Build

```bash
./gradlew :<module>:build                          # one service, e.g. :openbank-ledger-service:build
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```

CI is path-scoped — only changed services build (ADR-0040). Before opening a PR, verify the same gates
CI enforces (ADR-0029): PR (no direct `main` commits), automatic per-service releases, Conventional-Commit message,
`openapi.yaml` + contract test for API changes, Flyway migration for DB changes, tests for new behavior,
and a threat model for money-path services (ADR-0030). See [CONTRIBUTING.md](CONTRIBUTING.md) for the
full checklist. The portable governance entry point is
`python3 .github/scripts/run-gates.py --list`; run the relevant gates with `--only` or
`--group`, or the complete suite with `--all`. A local `/ship-check` skill is optional tooling.

---

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — architecture hub, overview, and contributor guide
- [`docs/repository-layout.md`](docs/repository-layout.md) — monorepo directory map
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

New here? Pick a [good first issue](https://github.com/JiRaska/open-bank-oss/labels/good-first-issue),
follow [CONTRIBUTING.md](CONTRIBUTING.md), and build one affected module before expanding
scope. Share reproducible setup failures through the issue templates.

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
