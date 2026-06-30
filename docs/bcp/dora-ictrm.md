# DORA ICT Risk Management Framework — OpenBank

Date: 2026-06-30
Regulation: EU 2022/2554 (DORA), applicable from 17 January 2025
Supervisory framework: ČNB DORA supervisory expectations
Owner: CTO / Maintainer Lead

---

## Article mapping

| DORA Article | Requirement | OpenBank control | Status |
|---|---|---|---|
| Art. 5 — ICT risk management framework | Documented governance body | BCP Owner role in bcp-policy.md §5 | ✓ |
| Art. 6 — ICT risk management system | Identify, classify, and mitigate ICT risks | Threat models (`docs/threat-models/`); ADR-0030; rules.yaml money_path_services | ✓ |
| Art. 7 — ICT systems, protocols, tools | Business-critical systems identified and hardened | T0–T3 classification in bcp-policy.md §2; Kyverno policies; NetworkPolicies | ✓ |
| Art. 8 — Identification | Maintain asset register | `service-graph.json` (ADR-0029 D3); fleet port map; SBOM per service (ADR-0121) | ✓ |
| Art. 9 — Protection and prevention | Access control, encryption, patch management | OpenBao secrets; mTLS Kafka (ADR-0137); OPA authz (ADR-0034); Keycloak OIDC | ✓ |
| Art. 10 — Detection | Continuous monitoring | Grafana + Pyrra SLOs; GoAlert alerting; Falco runtime; HolmesGPT; GlitchTip | ✓ |
| Art. 11 — Response and recovery | BCP, DRP, RTO/RPO | bcp-policy.md; runbooks 0001–0005; CNPG S3 WAL; ArgoCD rollback | ✓ |
| Art. 11(3)(c) — DR plan | Documented, tested DR plan | runbook-0002; dr-test-log.md; quarterly table-top exercise | ✓ |
| Art. 12 — Backup policies | Backup and restore procedures | CNPG continuous WAL + 14-day retention; S3 lifecycle rule | ✓ |
| Art. 13 — Learning and evolving | Post-incident review, lessons learned | dr-test-log.md §3; OpenBank issue tracker | ✓ |
| Art. 14 — Communication | Crisis communication plan | bcp-policy.md §5; GoAlert on-call | ✓ |
| Art. 17 — ICT-related incident classification | Classify by impact | GlitchTip severity + Pyrra burn-rate; T0–T3 tiers | ✓ |
| Art. 19 — Notification to regulators | Report major incidents within 4 h | BCP Owner responsibility; bcp-policy.md §5 | ✓ |
| Art. 28 — ICT third-party risk | Manage critical third-party providers | ADR-0027 cloud-agnostic; in-cluster OSS preference; vendor risk register (pending) | ~ |
| Art. 30 — Contractual arrangements | ICT contracts with providers | AWS ToS; Hetzner ToS; GitHub Enterprise (if applicable) | ~ |

Legend: ✓ = control in place  ~= partially addressed, follow-up in backlog

---

## Key technical controls

### Confidentiality

- OpenBao secret management (replaced HashiCorp Vault) — all secrets injected at runtime
- mTLS on all Kafka inter-service communication (ADR-0137 fleet sweep complete)
- OPA unified authorisation sidecar (ADR-0034) — policy-gated access to all endpoints
- Keycloak OIDC / OAuth 2.0 — customer and operator authentication
- Network policies (Kubernetes) — east-west traffic isolation per service

### Integrity

- Cosign image signing + KMS (ADR-0121 Axis 3) — image provenance verified at deploy time
- Kyverno `image-verify` policy — blocks unsigned or tampered images
- SBOM per service (`/q/openbank/sbom`) — supply chain transparency
- Flyway database migrations — schema integrity enforced at startup
- Kafka idempotent producers + transactional outbox — event integrity

### Availability

- CNPG S3 WAL archiving — continuous database backup (RPO 15 min for T0)
- Karpenter — node auto-replacement within ~30 min
- ArgoCD GitOps — infrastructure state recoverable from git in < 1 h
- Kafka replication factor 1 (sandbox), 3 (production) — message durability
- Per-service liveness + readiness probes — automatic pod restart on failure

---

## Risk register (summary)

| Risk ID | Risk description | Likelihood | Impact | Mitigating control | Residual |
|---------|-----------------|-----------|--------|-------------------|---------|
| R-01 | Database data loss | Low | Critical | CNPG WAL S3; 14-day retention; PITR | Low |
| R-02 | Kafka message loss | Low | High | 7-day retention; idempotent producers | Low |
| R-03 | Secret compromise | Low | Critical | OpenBao; break-glass in AWS SM; mTLS | Low |
| R-04 | Image supply chain attack | Low | Critical | Cosign + KMS; SBOM; Kyverno verify | Low |
| R-05 | Single-AZ node failure | Medium | High | Karpenter replacement; ArgoCD resync | Medium |
| R-06 | Multi-region outage | Low | Critical | M6 not yet deployed; T0 RTO = 4 h only | High until M6 |
| R-07 | Third-party cloud failure | Low | Critical | Cloud-agnostic architecture (ADR-0027) | Medium |
| R-08 | Insider threat / misconfiguration | Low | High | OPA authz; Falco; 2-approver money-path | Low |

---

## Follow-up backlog (Art. 28/30)

- [ ] Vendor risk register for critical third-party ICT providers (AWS, Strimzi, CNPG, Keycloak)
- [ ] Contractual DORA Art. 30 clause in service agreements (relevant once commercially licensed)
- [ ] Multi-region architecture (Milestone M6) to achieve T0 RTO ≤ 30 min
