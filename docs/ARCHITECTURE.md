# OpenBank — Architecture Overview

> This document is a C4-style human narrative. Authoritative decision records live in
> [`docs/adr/`](adr/) — each section below references the relevant ADR by number.

---

## 1. Context — what OpenBank is and who it talks to

OpenBank is a cloud-native, open-source retail **banking platform**. It is not a bank itself; it is
software that an organisation with the appropriate banking licence may deploy.

```
                        ┌──────────────────────────────────────────────────────┐
                        │                   OpenBank Platform                  │
                        │                                                      │
  Retail customer ──────┤  Customer mobile app (KMP/Compose, separate repo)   │
  (iOS / Android)       │  Customer edge BFF (openbank-customer-edge)          │
                        │                                                      │
  Bank operator ────────┤  Admin UI (Next.js, openbank-admin-ui)              │
  (internal staff)      │                                                      │
                        │  30+ backend microservices (Kotlin + Quarkus)        │
                        │  Shared library (openbank-libs)                      │
                        └───────────────┬──────────────────────────────────────┘
                                        │
          ┌─────────────────────────────┼─────────────────────────────┐
          │                             │                             │
    Keycloak (IAM)            Apache Kafka (messaging)        PostgreSQL (per-service DB)
    OpenBao (secrets)         Apicurio (schema registry)      Valkey (cache)
    OPA (policy)              Temporal (durable workflows)
          │                             │
    AWS EKS (sandbox)         External: clearing networks, EUDI hub,
    ArgoCD (GitOps)                      KYC/AML feeds (stubs in sandbox)
```

**External actors:**

| Actor | Role |
|---|---|
| Retail customer | Uses the mobile app (KMP + Compose) or the PSD2 XS2A developer portal |
| Bank operator | Uses admin-ui for KYC approval, ledger reconciliation, monitoring |
| TPP (Third-Party Provider) | PSD2 AISP/PISP APIs via developer portal (developer.open-bank.tech) |
| EUDI hub | PID digital-identity credential exchange (OpenID4VP + OpenID4VCI) |
| Clearing networks | Simulated in sandbox (ISO 20022 clearing simulator) |
| AWS | EKS hosting, ECR images, Secrets Manager, S3, VPC endpoints |

---

## 2. Container diagram — runtime components

### Frontend tier

| Container | Technology | Purpose |
|---|---|---|
| `openbank-admin-ui` | Next.js 16 / React 19 / TypeScript | Bank operator console — KYC, ledger, service catalog, observability |
| `openbank-customer-edge` | Quarkus / Kotlin (BFF) | Mobile app backend-for-frontend; OPA enforce mode for authz |
| `openbank-developer-portal` | Static site | PSD2 XS2A API explorer with WAF/ModSecurity (developer.open-bank.tech) |

### Core banking services

| Service | Port | Bounded context |
|---|---|---|
| `openbank-account-service` | 8100 | Account lifecycle (open, freeze, close) |
| `openbank-ledger-service` | 8101 | Double-entry general ledger |
| `openbank-transaction-service` | 8102 | Transaction saga orchestration, idempotency |
| `openbank-balance-service` | 8103 | Real-time balance projection |
| `openbank-product-catalog` | 8104 | Banking product catalog |

### Payments

| Service | Port | Bounded context |
|---|---|---|
| `openbank-sepa-payment` | 8115 | SEPA Credit Transfer |
| `openbank-domestic-payment` | 8116 | Domestic (CERTIS-style) payment |
| `openbank-sepa-instant` | 8127 | SEPA Instant Credit Transfer (10s window) |
| `openbank-clearing-service` | 8124 | Clearing & net settlement |
| `openbank-clearing-simulator` | 8139 | ISO 20022 clearing simulator (ADR-0104) |
| `openbank-settlement-service` | 8138 | Net settlement & reconciliation |
| `openbank-swift-service` | 8122 | SWIFT MT/MX messaging |
| `openbank-standing-order-service` | 8121 | Recurring payments (daily due-date sweep) |
| `openbank-sdd-service` | 8129 | SEPA Direct Debit mandates |

### Identity, auth & compliance

| Service | Port | Bounded context |
|---|---|---|
| `openbank-pid-service` | 8105 | Party identity, dedup; EUDI/PID (ADR-0072/0094) |
| `openbank-party-service` | 8111 | Customer master data |
| `openbank-consent-service` | 8106 | PSD2 consent management |
| `openbank-psd2-service` | 8107 | PSD2 AISP/PISP API |
| `openbank-tpp-registry-service` | 8108 | Third-party provider registry |
| `openbank-sca-service` | 8110 | Strong Customer Authentication (passkey + OTP) |
| `openbank-kyc-service` | 8114 | Know-Your-Customer onboarding |
| `openbank-aml-service` | 8117 | Anti-money-laundering screening |
| `openbank-sanctions-service` | 8123 | Sanctions list screening (pg_trgm fuzzy match) |
| `openbank-onboarding-service` | 8130 | Onboarding funnel projection |

### Risk, operations & AI

| Service | Port | Bounded context |
|---|---|---|
| `openbank-fraud-service` | 8133 | Fraud detection — velocity-counter signal plane (ADR-0084) |
| `openbank-agent-service` | 8109 | AI agent integration (MCP, policy-gated, ADR-0031) |
| `openbank-copilot-service` | 8131 | Customer AI assistant (LLM, sandbox only) |
| `openbank-devops-agent` | — | DevOps/DORA AI agent (ADR-0119) |
| `openbank-finops-agent` | 8141 | FinOps cost/usage AI agent |

### Supporting services

| Service | Port | Bounded context |
|---|---|---|
| `openbank-notification-service` | 8112 | Customer notifications (push, email) |
| `openbank-audit-service` | 8113 | Audit trail aggregation |
| `openbank-card-issuance-service` | 8118 | Card issuance |
| `openbank-fx-service` | 8119 | Foreign exchange, multi-currency revaluation |
| `openbank-interest-service` | 8125 | Interest calculation & accrual |
| `openbank-lending-service` | 8126 | Loan origination & servicing (four-eyes gate) |
| `openbank-dispute-service` | 8135 | Card disputes & chargebacks |
| `openbank-statement-service` | 8136 | Account statements (camt.053 / MT940 / PDF) |
| `openbank-anacredit-service` | 8137 | AnaCredit regulatory report builder |
| `openbank-finrep-service` | 8140 | FINREP / COREP regulatory reporting |
| `openbank-analytics-sink` | 8134 | Event analytics sink |
| `openbank-security-scanner` | 8120 | Internal security scanning |
| `openbank-simulation` | — | Deterministic simulation harness (DST, ADR-0100) |

### Shared infrastructure (not microservices)

| Component | Role |
|---|---|
| `openbank-libs` | Shared Kotlin primitives: Money, IBAN, idempotency key, transactional outbox, audit event, `ServiceInfoResource`, `ApiVersionResponseFilter`, OPA authz client |
| `openbank-api-gateway` | Kong gateway configuration (rate limiting, auth, routing) |

---

## 3. Key architectural decisions

### 3.1 Hexagonal architecture per service (ADR-0002)

Every JVM service follows **ports-and-adapters** (hexagonal) architecture:

```
src/main/kotlin/<base-package>/
  domain/
    model/        — Entities, aggregates, value objects (pure Kotlin, zero framework imports)
    event/        — Domain events
    service/      — Domain services
  application/
    port/in/      — Use case interfaces (commands, queries)
    port/out/     — Repository + event publisher + external client interfaces
    usecase/      — Use case implementations (orchestration only)
  infrastructure/
    persistence/  — JPA / Panache repositories, Flyway migrations
    messaging/    — Kafka producers, consumers, transactional outbox (ADR-0003)
    rest/         — REST resources, DTOs, exception mappers
    client/       — Outbound HTTP clients
    config/       — Quarkus configuration
```

**The domain layer has zero framework imports** — CI enforces this with a Detekt rule.
This means domain logic can be unit-tested without a container, and the infrastructure adapters
can be swapped (e.g., changing the broker or DB) without touching business rules.

Shared plumbing (outbox, audit, IBAN, Money) lives in `openbank-libs` so it is not reimplemented
per service.

### 3.2 Event-driven with transactional outbox (ADR-0003)

Services communicate via Apache Kafka. State mutations are written to a local PostgreSQL **outbox
table** in the same transaction as the business entity change, and a relay publishes them to Kafka.
This eliminates the dual-write problem and guarantees at-least-once delivery without distributed
transactions.

```
  Service A                          Kafka                   Service B
  ─────────                          ─────                   ─────────
  BEGIN TX
  UPDATE entity
  INSERT outbox_event                              <── Relay reads + publishes ──>  ConsumerRecord
  COMMIT TX
```

Schemas are registered in Apicurio Schema Registry (AsyncAPI for Kafka topics, ADR-0006).

### 3.3 Governance as code (ADR-0029)

Rules that govern the monorepo live in a **machine-readable** file:

```
openbank-libs/governance/rules.yaml
```

This file is the single source of truth. CI gates read it directly; the agent guide (`CLAUDE.md`)
is a human summary. When they conflict, `rules.yaml` wins.

Key invariants enforced by CI:

| Rule | Enforcement |
|---|---|
| No direct commits to `main` | Branch protection + squash-merge only |
| Per-service SemVer (`feat`→minor, `fix`→patch) | `version.txt` bumped per commit type |
| Conventional Commits format | `commitlint` + release-please |
| Changelogs are auto-generated | release-please from Conventional Commits |
| Domain layer has zero framework imports | Detekt custom rule |
| No duplicate YAML keys in `application.yaml` | `check-duplicate-yaml-keys.sh` |
| OpenAPI spec updated for API changes | `oasdiff` in CI |
| Flyway migration for DB changes | Migration presence check |

The service catalog (`service-graph.json`, `catalog.json`) is **CI-derived**, never hand-edited.

### 3.4 Money-path services — two approvals + threat model (ADR-0030)

Services that handle money movement are classified as **money-path**:

> ledger, transaction, payment (sepa, domestic, instant, sdd, swift, clearing), balance, fx,
> lending, settlement, standing-order

These services require:

1. **Two human approvals** before merge (CODEOWNERS enforced)
2. **A threat model** in `docs/threat-models/<service>.md` (STRIDE/DFD, checked by CI)
3. **Higher test-coverage floors** (ratchet-only, never reduced)

The money-path list is authoritative in `rules.yaml: money_path_services`.

### 3.5 Unified OPA authorization (ADR-0034)

Authorization uses **Open Policy Agent** as the single policy decision point for both:

- **REST endpoints** — Quarkus interceptor (`@Authorize("scope", resource = "#id")`) queries the
  local OPA sidecar before executing the use case.
- **AI agent MCP tool calls** — `openbank-agent-service` routes every `/tools/call` through OPA
  before dispatching, enforcing the charter in `openbank-libs/governance/agents.yaml`.

A single OPA sidecar per pod serves both request paths. Policies live in
`openbank-infra/opa/policies/` as Rego; the shared data bundle is `data.openbank.*`.

This means every authorization decision — whether from a human REST call or an AI agent — produces
an audit record and is governed by the same Rego policy chain.

### 3.6 Dual version axes (ADR-0048)

Each service has **two independent version numbers** that move on different cadences:

| Axis | Source | Who moves it | Cadence |
|---|---|---|---|
| **Release version** | `<service>/version.txt` | release-please (from Conventional Commits) | Every `feat`, `fix`, `perf` commit |
| **API contract version** | `<service>/src/main/resources/META-INF/openapi.yaml: info.version` | Developer (oasdiff classifies the bump) | Only when the REST contract changes |

The API contract major version also appears in the URL path (`/api/v{N}`). Forcing them equal
(as ADR-0029 D2 originally proposed) was a mistake: every internal bug-fix would silently rewrite
the "API contract version", making it useless as a compatibility signal for consumers.

`/api/v1/info` reports both: `version` (release) and `apiVersion` (contract).

---

## 4. Deployment architecture

### Cloud substrate

OpenBank runs on **Kubernetes** (AWS EKS in the sandbox). The infrastructure is managed with
**OpenTofu** (Terraform-compatible) and deployed via **ArgoCD GitOps** (ADR-0010):

```
  Git repo (this repo)
       │
       ▼
  GitHub Actions CI
  (path-scoped: only changed services build)
       │  image push to ECR
       ▼
  openbank-gitops repo
  (ArgoCD watches, auto-deploys on image tag change)
       │
       ▼
  AWS EKS cluster
  (ArgoCD Application per service)
```

CI is **path-scoped** — only services whose files changed are built and deployed. This keeps
CI fast and prevents unrelated churn from triggering rebuilds.

### Runner fleet (FinOps order)

1. **Hetzner (x86) + Mac mini (ARM)** — primary, always-on, zero AWS cost
2. **ARC on AWS Spot** — overflow burst only; `minRunners=0` (scale-to-zero)

Never route primary builds to ARC; never remove Hetzner from the label pool to "fix" an
architecture issue — fix the Dockerfile instead.

### Observability stack

```
  Services (Quarkus)
  ──────────────────
  OpenTelemetry SDK → Otel Collector
                           │
                    ┌──────┼──────────────────┐
                    │      │                  │
               Prometheus  Loki (logs)    Tempo (traces)
                    │      │                  │
                    └──────┼──────────────────┘
                           │
                       Grafana dashboards
                       Pyrra (SLO-as-code)
                       GoAlert (on-call)
                       GlitchTip (error tracking)
                       Pyroscope (continuous profiling)
```

Domain-level metrics (`DomainMetrics`) are emitted by every service via a shared pattern in
`openbank-libs` — counter, histogram, and gauge per bounded context.

---

## 5. Security architecture

| Layer | Mechanism |
|---|---|
| Identity | Keycloak 24 (JWT Bearer; realm `openbank`); EUDI PID for digital identity |
| Secrets | OpenBao (LF fork of HashiCorp Vault) with pod-identity injection |
| Authorization | OPA sidecar per pod (REST + MCP, ADR-0034) |
| Transport | mTLS via Istio (planned); HTTPS at ingress (Kong) |
| Supply chain | SBOM (CycloneDX), container signing (cosign + KMS), SLSA provenance (ADR-0030) |
| SAST / SCA | CodeQL, Trivy, gitleaks — gated in CI |
| Pen testing | External pen test P0–P2 complete; findings remediated (ADR-0030) |
| AI governance | Policy-gated MCP tools, HITL gates, AI-attributed audit (ADR-0031) |
| Threat models | STRIDE/DFD for every money-path service in `docs/threat-models/` |

---

## 6. Where to go next

| Question | Go here |
|---|---|
| How do I contribute? | [`CONTRIBUTING.md`](../CONTRIBUTING.md) |
| What is the project roadmap? | [`docs/ROADMAP.md`](ROADMAP.md) |
| Full decision history | [`docs/adr/README.md`](adr/README.md) |
| How is it deployed? | [`DEPLOYMENT.md`](../DEPLOYMENT.md) |
| Security policy | [`SECURITY.md`](../SECURITY.md) |
| Authoritative rules (CI reads this) | [`openbank-libs/governance/rules.yaml`](../openbank-libs/governance/rules.yaml) |
