# Technology Radar

> Last updated: 2026-05-26
> Status: **v0.1** — initial radar. Reviewed quarterly.
> Format inspired by ThoughtWorks Technology Radar (Adopt / Trial / Assess / Hold).

## How to read

- **Adopt** — Battle-tested in OpenBank or industry-proven; default choice for new work.
- **Trial** — Promising; in active use in at least one OpenBank module; safe for new work after team review.
- **Assess** — Worth exploring on a non-critical path; do not bet a service on it yet.
- **Hold** — Do not introduce in new code. Existing usages should have a deprecation plan.

## Languages and runtimes

| Item | Ring | Rationale |
|---|---|---|
| Kotlin 2.x on JVM 21 | Adopt | Primary backend language; null-safety, coroutines, Quarkus-friendly |
| TypeScript 5.x strict | Adopt | All frontend and BFF code; strict mode mandatory |
| Python 3.12 | Trial | For data/ML/agent pipelines and scripting only — never on banking request path |
| Go | Assess | Considered for hot-path proxies / sidecars only; no general adoption |
| Java 21 | Trial | Acceptable when integrating with Java-only libs; new services prefer Kotlin |
| Scala | Hold | No new code; complexity not justified for our domain |
| Node.js as backend runtime | Hold | Frontend tooling only; not for banking services |

## Frameworks

| Item | Ring | Rationale |
|---|---|---|
| Quarkus 3.x | Adopt | Native-compileable, fast startup, Kotlin-first, MicroProfile, JVM observability |
| Spring Boot | Hold | Allowed only inside `attic/`; do not introduce in new services |
| Ktor | Assess | Lighter than Quarkus; consider for narrow edge services |
| Next.js 15 (App Router) | Adopt | Primary web framework for user-facing UI |
| React 19 | Adopt | Default UI library |
| Tailwind CSS 4 | Adopt | Default styling system |
| shadcn/ui | Adopt | Component baseline |
| Remix | Hold | Migrated to Next.js; do not reintroduce |
| Angular | Hold | Not in stack |

## Persistence

| Item | Ring | Rationale |
|---|---|---|
| PostgreSQL 16+ | Adopt | Primary OLTP store; per-service schema or per-service database |
| Flyway | Adopt | Migration tool; one migration directory per service |
| Liquibase | Hold | Do not introduce |
| Kafka 3.7+ (KRaft mode) | Adopt | Event backbone, outbox sink, audit pipeline |
| Schema Registry (Confluent / Apicurio) | Adopt | Avro/Protobuf schema enforcement for all topics |
| Debezium | Adopt | CDC outbox pattern from Postgres to Kafka |
| Redis 7+ | Trial | Idempotency cache, rate limiting, short-lived state only |
| Elasticsearch / OpenSearch | Trial | Search, audit log query, full-text only |
| MongoDB | Hold | Avoid for new services; relational model preferred for banking |
| Cassandra | Hold | Operational complexity not justified at our scale |
| EventStoreDB | Assess | Event sourcing primitive; vs Kafka-as-log debate ongoing |

## API and integration

| Item | Ring | Rationale |
|---|---|---|
| OpenAPI 3.1 + design-first | Adopt | Every public/external service MUST publish OpenAPI 3.1; specs live in `openbank-contracts/` |
| Stoplight / Spectral linting | Adopt | OpenAPI quality gate in CI |
| gRPC | Trial | Inter-service east-west when latency matters; mTLS-only |
| GraphQL | Hold | Do not introduce in core banking; permissible only at BFF |
| AsyncAPI 3.x | Adopt | All Kafka topics MUST be described in AsyncAPI |
| JSON:API | Hold | Custom REST conventions per BIAN; no JSON:API |
| webhooks | Trial | For TPP/PSD2 callbacks only; signed with eIDAS certs |

## Authentication, authorisation, identity

| Item | Ring | Rationale |
|---|---|---|
| Keycloak 25+ | Adopt | IdP, SCA orchestration target |
| OAuth 2.1 + PKCE | Adopt | Default for all clients |
| OIDC | Adopt | All human auth |
| FAPI 2.0 (PSD2 profile) | Adopt | Required for AISP/PISP endpoints |
| OPA / Cedar | Trial | Policy as code for authz decisions |
| SPIFFE / SPIRE | Assess | Workload identity for service-to-service mTLS |
| LDAP-only auth | Hold | Direct LDAP not allowed; broker through Keycloak |
| Self-rolled JWT issuance | Hold | Never — Keycloak only |

## Secrets and PKI

| Item | Ring | Rationale |
|---|---|---|
| HashiCorp Vault | Adopt | Primary secret store, dynamic DB credentials, PKI |
| External Secrets Operator | Adopt | K8s integration to Vault |
| cert-manager | Adopt | TLS lifecycle |
| AWS KMS / GCP KMS | Trial | Cloud-native KMS as Vault auto-unseal backend |
| Hardware Security Modules (HSM, FIPS 140-3 L3) | Trial | Required for production PSD2 signing keys |
| .env files in repo | Hold | Forbidden; gitleaks blocks this |

## Containers and orchestration

| Item | Ring | Rationale |
|---|---|---|
| Docker (BuildKit) | Adopt | Local dev and CI |
| Kubernetes 1.30+ | Adopt | Production runtime |
| Helm 3 | Adopt | Packaging; one chart per service |
| ArgoCD | Adopt | GitOps deployment |
| Istio | Trial | Service mesh; mTLS, traffic policy |
| Linkerd | Assess | Lighter mesh alternative to Istio |
| Cilium | Trial | eBPF network policy; CNI of choice |
| Knative | Hold | Not yet needed; revisit if usage warrants |

## Observability

| Item | Ring | Rationale |
|---|---|---|
| OpenTelemetry (traces, metrics, logs) | Adopt | All services MUST emit OTel signals |
| Prometheus | Adopt | Metric storage |
| Grafana | Adopt | Dashboards |
| Loki | Adopt | Log aggregation |
| Tempo / Jaeger | Adopt | Distributed tracing |
| Alertmanager | Adopt | Alert routing |
| SigNoz | Assess | Unified OTel backend alternative |
| ELK (Elasticsearch + Logstash + Kibana) | Hold | Loki preferred; ELK acceptable when org already has it |
| Datadog | Hold | Vendor lock-in; commercial adopters may use it |

## Resilience patterns

| Item | Ring | Rationale |
|---|---|---|
| Outbox pattern (transactional event publish) | Adopt | Every service writing to Kafka MUST use outbox |
| Saga (orchestration or choreography) | Adopt | All multi-service transactions |
| Idempotency keys on POST | Adopt | All write endpoints |
| Circuit breaker (Resilience4j) | Adopt | All synchronous external calls |
| Bulkhead | Adopt | Thread/connection pool isolation per downstream |
| Two-phase commit (XA) | Hold | Never; use saga + outbox |
| Distributed locks via Redis | Trial | Acceptable for short-lived locks; avoid as concurrency primitive |

## Testing

| Item | Ring | Rationale |
|---|---|---|
| JUnit 5 + Kotest | Adopt | Unit tests |
| Testcontainers | Adopt | Integration tests; real Postgres, Kafka, Redis |
| Pact (consumer-driven contracts) | Trial | For inter-service contracts on critical paths |
| WireMock | Adopt | HTTP stubbing for unit/integration tests |
| Cypress / Playwright | Adopt | E2E browser tests; Playwright preferred |
| k6 | Adopt | Load tests, performance gates in CI |
| Chaos Mesh / LitmusChaos | Trial | Chaos engineering in staging |
| Selenium | Hold | Playwright/Cypress preferred |

## Build and CI/CD

| Item | Ring | Rationale |
|---|---|---|
| Gradle 8.x with version catalogs | Adopt | JVM build tool; one root build |
| pnpm + Turborepo | Adopt | Frontend monorepo build |
| GitHub Actions | Adopt | Primary CI/CD |
| GitLab CI | Trial | For mirrored repos |
| Dependabot + Renovate | Adopt | Dependency upgrades |
| Sigstore / cosign | Adopt | Container signing |
| SLSA Level 3 build provenance | Trial | Aspirational target |
| Maven | Hold | Gradle only |

## Security tooling

| Item | Ring | Rationale |
|---|---|---|
| Trivy | Adopt | Container + IaC scanning |
| Grype | Trial | Alternative SBOM scanner |
| Syft | Adopt | SBOM generation |
| gitleaks | Adopt | Secret scanning (already wired) |
| Semgrep | Adopt | SAST |
| OWASP Dependency-Check | Adopt | Dependency CVE scan |
| ZAP / Burp Suite | Trial | DAST in staging |
| Falco | Trial | Runtime security |
| OpenSCAP / Lynis | Trial | Host hardening audit |

## AI / ML

| Item | Ring | Rationale |
|---|---|---|
| MCP (Model Context Protocol) | Trial | AI agent integration (`openbank-agent-service`) |
| LangChain | Hold | Excessive abstraction; build thin clients instead |
| LlamaIndex | Assess | RAG over internal docs |
| OpenAI API | Trial | Internal tooling only; never on customer banking path |
| Self-hosted LLMs (vLLM, llama.cpp) | Trial | When data residency / DORA require it |
| ML for fraud scoring | Trial | XGBoost / lightgbm; isolation forests |

## Cloud platforms

| Item | Ring | Rationale |
|---|---|---|
| Cloud-agnostic K8s deployment | Adopt | Primary distribution mode |
| AWS | Trial | Reference cloud target |
| GCP | Trial | Reference cloud target |
| Azure | Trial | Reference cloud target |
| Hetzner / OVH bare-metal K8s | Trial | EU sovereignty target |
| Single-cloud lock-in | Hold | Always provide cloud-agnostic option |

## Hold list — explicitly forbidden in new code

- Java EE / JEE servers (WildFly, JBoss EAP, WebLogic) — Quarkus replaces them
- jQuery — React only
- AngularJS / Angular 2-17 — Next.js / React only
- MongoDB as primary store
- Self-signed JWT issuers
- Sync XA / 2PC transactions across services
- Long-lived shared secrets in env vars (use Vault)
- `as any` / `@ts-ignore` / `@ts-expect-error` (already blocked in CONTRIBUTING)
- Empty `catch {}` blocks

## Quarterly review cadence

Radar is reviewed every quarter by maintainers. Movement between rings requires a one-paragraph rationale recorded in the commit message changing this file.
