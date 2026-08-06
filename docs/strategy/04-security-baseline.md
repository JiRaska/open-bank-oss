# Security Baseline

> Last updated: 2026-05-26
> Status: **v0.1** — target controls. Implementation status tracked per service in service README.
> Frameworks: **NIST Cybersecurity Framework 2.0**, **OWASP ASVS 4.0 Level 3**, **OWASP API Security Top 10 (2023)**, **CIS Kubernetes Benchmark 1.9**, **PCI DSS v4.0**, **DORA Art. 6-15**.

## Threat model summary

OpenBank operates in a hostile environment with three adversary classes:

1. **External attackers** — financially motivated; account takeover, fraudulent payments, data theft for resale.
2. **Insider threats** — privileged operators, compromised credentials, malicious developers.
3. **State actors** — espionage, payment manipulation, infrastructure denial; relevant for systemically important deployments.

Primary attack surfaces:
- Public PSD2 APIs (TPP integration)
- Customer mobile/web channels
- Internal admin and operator UIs
- Inter-service Kafka and REST traffic
- CI/CD pipeline and supply chain
- Cloud control plane (K8s, IAM)
- Database backups and snapshots

## Defence in depth — layered controls

### Layer 1: Network

| Control | Status target | Notes |
|---|---|---|
| All ingress through WAF (ModSecurity OWASP CRS or vendor) | Required | Block OWASP Top 10 patterns at edge |
| Public exposure only via API Gateway (Kong) | Required | No direct service exposure |
| mTLS between all services in cluster | Required | Istio / Linkerd / SPIFFE-based |
| Default-deny NetworkPolicy in K8s | Required | Whitelist explicit flows only. **Not met**: 108 explicit-allow policies, 0 default-deny baselines, 24 of 74 namespaces with no policy at all (measured 2026-08-06). Staged rollout in #2691; the measurement stage is runbook 0010. |
| Egress filtering — no service may dial arbitrary internet | Required | Egress gateway with allowlist |
| Private cluster control plane | Required | No public K8s API |
| DNS over HTTPS / DNSSEC | Required | Prevent DNS hijacking |
| DDoS protection at edge | Required | Cloud provider native or Cloudflare |

### Layer 2: Identity and access

| Control | Status target | Notes |
|---|---|---|
| Keycloak as sole IdP | Required | No service issues its own JWTs |
| OAuth 2.1 + PKCE for all clients | Required | No legacy implicit / password grants |
| OIDC for all human auth | Required | SSO required |
| FAPI 2.0 baseline for PSD2 endpoints | Required | Per EBA RTS |
| Strong Customer Authentication (PSD2 Art. 97) | Required | Two of three factors; dynamic linking |
| Workload identity (SPIFFE / K8s ServiceAccount) | Required | No long-lived service credentials |
| RBAC + ABAC via OPA / Cedar | Required | Policy as code |
| Just-in-time elevation for operators | Required | Time-bound role assignment |
| No shared accounts | Required | Every action attributable to a human or workload |
| MFA for all operator accounts | Required | WebAuthn / FIDO2 preferred |
| Quarterly access review | Required | Auditable |

### Layer 3: Data

| Control | Status target | Notes |
|---|---|---|
| TLS 1.3 only (TLS 1.2 deprecated by Jan 2027) | Required | Disable TLS 1.0/1.1 entirely |
| Postgres encryption at rest | Required | KMS-managed keys |
| Per-tenant encryption keys for sensitive data | Trial | Envelope encryption with KMS |
| Field-level encryption for card PAN, IBAN where legally feasible | Required | PCI DSS Req 3 |
| Tokenisation of PAN | Required | Card service must tokenise; raw PAN never persisted in non-card services |
| Cryptography: AES-256-GCM, RSA-3072 / Ed25519, SHA-256+ | Required | No MD5, SHA-1, DES, 3DES, RC4 |
| Post-quantum readiness | Assess | Track NIST PQC standards (ML-KEM, ML-DSA) |
| Database backups encrypted | Required | Tested restore quarterly |
| Right to be forgotten (GDPR Art. 17) | Required | Soft-delete in OLTP, crypto-shred in backups |
| Data classification: Public / Internal / Confidential / Restricted | Required | Tagged in code and schema |
| PII minimisation | Required | No PII in logs without redaction |

### Layer 4: Application

| Control | Status target | Notes |
|---|---|---|
| OWASP ASVS L3 baseline for all services | Required | Verifiable in CI where possible |
| Input validation at API boundary (allowlist) | Required | Bean Validation + custom validators |
| Output encoding context-aware | Required | XSS prevention in UI layers |
| CSRF protection on stateful endpoints | Required | SameSite=Strict cookies, double-submit tokens |
| SQL injection — parameterised queries only | Required | No string concatenation in SQL |
| Authn/authz checks on EVERY endpoint | Required | Default-deny at framework level |
| Rate limiting per client, per endpoint | Required | At gateway and per service |
| Idempotency keys on all write endpoints | Required | Already a hard requirement |
| Audit log for every state change | Required | Sink to `audit-events-out` Kafka topic |
| No type-safety bypasses | Required | `as any`, `@ts-ignore` blocked in CI |
| Dependency vulnerability scan on every PR | Required | OWASP Dependency-Check, Trivy |
| SAST on every PR | Required | Semgrep, gitleaks |
| Software Bill of Materials (SBOM) per release | Required | Syft CycloneDX format |

### Layer 5: Supply chain

| Control | Status target | Notes |
|---|---|---|
| All container images signed with cosign | Required | Verify at admission |
| SLSA Level 3 build provenance | Trial | Target end of M5 |
| Reproducible builds | Assess | Per service |
| Dependency pinning by hash | Required | Lock files committed |
| Trusted base images only (distroless, chainguard) | Required | No `latest` tags |
| Allowlist of approved registries | Required | Pull only from internal registry mirror |
| DCO + signed commits | Required | Already enforced in CONTRIBUTING |

### Layer 6: Runtime

| Control | Status target | Notes |
|---|---|---|
| CIS Kubernetes Benchmark 1.9 conformance | Required | kube-bench in CI |
| Pod Security Standards: Restricted | Required | Enforced via admission |
| Read-only root filesystem | Required | Per pod |
| Non-root user in container | Required | UID > 10000 |
| Drop all Linux capabilities by default | Required | Add only what is needed |
| Seccomp profile applied | Required | RuntimeDefault minimum |
| AppArmor / SELinux where available | Trial | Per platform |
| Runtime security monitoring (Falco) | Trial | Detect anomalous syscalls |
| Memory limits + CPU limits set on every container | Required | Prevent resource exhaustion |
| Pod-to-pod traffic only via mesh | Required | NetworkPolicy enforced |

### Layer 7: Operational

| Control | Status target | Notes |
|---|---|---|
| Centralised logging with tamper-evidence | Required | Append-only log sink |
| Centralised metrics + alerting | Required | Prometheus + Alertmanager |
| Distributed tracing on every request | Required | OpenTelemetry |
| Security incident response runbook | Required | Per scenario; tabletop drill quarterly |
| Vulnerability disclosure programme | Required | GitHub Security Advisories (already in SECURITY.md) |
| Penetration test annually | Required | External provider |
| Threat modelling per major change | Required | STRIDE / LINDDUN |
| Backup tested restore — quarterly | Required | Documented evidence |
| Disaster recovery drill — semi-annually | Required | Full region failover |
| Key rotation calendar | Required | Quarterly for static keys, daily for dynamic |

## Mapping to NIST CSF 2.0 functions

| NIST CSF Function | Primary controls in this baseline |
|---|---|
| **Govern (GV)** | License, governance, supply chain, vendor risk, threat modelling |
| **Identify (ID)** | Asset inventory, data classification, threat model, SBOM |
| **Protect (PR)** | Layers 1-6 (network, identity, data, app, supply, runtime) |
| **Detect (DE)** | Logging, monitoring, SIEM, Falco, anomaly alerts |
| **Respond (RS)** | Incident runbooks, disclosure programme, on-call rotation |
| **Recover (RC)** | Backups, DR drills, post-incident review |

## OWASP API Security Top 10 (2023) — coverage matrix

| Risk | Primary control |
|---|---|
| API1: BOLA (Broken Object Level Authorization) | OPA policy + per-object authz check; covered by ASVS L3 V4 |
| API2: Broken Authentication | Keycloak + OAuth 2.1 + FAPI 2.0 |
| API3: BOPLA (Broken Object Property Level Authorization) | Response shaping per role; field-level access policies |
| API4: Unrestricted Resource Consumption | Rate limiting + quotas |
| API5: BFLA (Broken Function Level Authorization) | OPA + default-deny |
| API6: Unrestricted Access to Sensitive Business Flows | Velocity limits + behavioural detection |
| API7: SSRF | Egress allowlist, no user-supplied URLs in server fetches |
| API8: Security Misconfiguration | CIS K8s benchmark, hardening baselines, IaC scanning |
| API9: Improper Inventory Management | OpenAPI catalogue, versioning policy, deprecation calendar |
| API10: Unsafe Consumption of APIs | Validation of all third-party responses, circuit breakers |

## PCI DSS v4.0 scope and applicability

Card data (PAN, expiry, CVV) is handled exclusively by `openbank-card-issuance-service` and its dedicated infrastructure subnet. Other services operate **outside CDE** (Cardholder Data Environment) by tokenisation. PCI DSS Requirements 1-12 apply to CDE services.

Operators deploying OpenBank to handle cards must:
- Run CDE services in a network-segregated environment
- Engage a QSA for annual assessment
- Maintain PCI DSS evidence repository

## DORA Article-level alignment

| DORA Article | OpenBank control |
|---|---|
| Art. 5 — Governance | Documented architecture (this doc + ADRs) |
| Art. 6 — ICT risk framework | Risk register (`docs/strategy/08-risk-register.md`) |
| Art. 9 — Protection and prevention | Layers 1-6 above |
| Art. 10 — Detection | Detect controls (logging, monitoring) |
| Art. 11 — Response and recovery | Response/Recover controls |
| Art. 12 — Backup and restoration | Backup + DR drills |
| Art. 13 — Learning and evolving | Post-incident review process |
| Art. 14 — Communication | Vulnerability disclosure + customer comms templates |
| Art. 15 — RTS for ICT risk management | RTS 2024/1774 alignment |
| Art. 17-23 — Incident management | Major incident reporting to CNB |
| Art. 24-27 — Resilience testing | TLPT (Threat-Led Penetration Testing) annually for significant deployments |
| Art. 28-44 — Third-party ICT risk | Subprocessor register, contract clauses |

## Verification

Security controls are verified by:
- **CI gates** — gitleaks, Trivy, Semgrep, OWASP Dependency-Check, kube-bench, conftest (OPA on IaC), Spectral (OpenAPI), license-check, SBOM generation.
- **Pre-merge review** — security-impact label requires explicit reviewer approval.
- **Pen-test** — annual external test; results tracked in `docs/security/pentest-history.md` (to be created).
- **Tabletop drills** — quarterly incident response scenarios.
- **DR drills** — semi-annual region failover.

## Out of scope (intentional, with rationale)

- **Physical security** — operators must address data centre / colocation security.
- **HR security** — operators apply their own background-check policy.
- **Insurance and liability** — operator concern; Apache-2.0 disclaims warranty.

## Disclaimer

This baseline describes target controls for production deployment. The reference implementation in this repository does not yet implement all controls. Maturity status per service is tracked in each service's README under "Security posture".
