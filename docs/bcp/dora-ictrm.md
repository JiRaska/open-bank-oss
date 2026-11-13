# DORA ICT Risk Management Framework — OpenBank

Date: 2026-11-11
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
| Art. 11 — Response and recovery | BCP, DRP, RTO/RPO | bcp-policy.md (its runbook table names `runbook-0001`…`0005` files that do not exist under that name in `docs/runbooks/`; those numbers there are unrelated upgrade/migration runbooks); the only restore procedure is the drill record in `docs/runbooks/0003-postgresql-16-to-18-major-upgrade.md` § Restore Drill Result, plus `automated-dr-restore.md`; CNPG S3 WAL; ArgoCD rollback. Application-level RTO and RPO are unmeasured (see `dr-test-log.md`) | partial (#8347) |
| Art. 11(3)(c) — DR plan | Documented, tested DR plan | No dedicated DR runbook exists (`docs/runbooks/0002-*` is a Vault upgrade runbook, not a DR plan); restore procedure is `docs/runbooks/0003-postgresql-16-to-18-major-upgrade.md` § Restore Drill Result, `automated-dr-restore.md`, `dr-test-log.md`. One live restore drill (ledger database only, database-level RTO ~84 s); the other CNPG databases have none, and no failover has been exercised | partial (#8347) |
| Art. 12 — Backup policies | Backup and restore procedures | CNPG continuous WAL; CNPG `retentionPolicy` is 30 days on every ledger/service cluster in `openbank-infra/gitops` except the goalert database (7 days); the sandbox backup bucket lifecycle expires objects after 35 days (`openbank-infra/aws/envs/sandbox-platform/db-backups.tf`, the legal 10-year retention applies to prod and is not configured there). Restore tested for one database only; backups share the account and region of the data and have no object lock | partial (#8347, #15754) |
| Art. 13 — Learning and evolving | Post-incident review, lessons learned | the `Lessons learned` and `Actions` fields of each entry in `dr-test-log.md` (the log has no numbered sections); OpenBank issue tracker | ✓ |
| Art. 14 — Communication | Crisis communication plan | bcp-policy.md §5; GoAlert on-call | ✓ |
| Art. 17 — ICT-related incident management and classification | Classify by the RTS criteria (clients, transactions, duration, geography, data loss, criticality) | Operator-typed severity (P1–P4) on `IctIncident`; GlitchTip severity + Pyrra burn-rate; T0–T3 tiers. No classification criteria are evaluated in code | not met (#15752) |
| Art. 18–19 — Classification of major incidents and reporting | Initial, intermediate and final reports in the prescribed time limits | BCP Owner responsibility; bcp-policy.md §5. The incident record has a reported-to-regulator flag only; there are no staged reports, and the incident return is `bff-only` in `regulatory-obligations.yaml` | not met (#15752) |
| Art. 28 — ICT third-party risk | Register of information; documented and tested exit strategy | ADR-0174 holds the register as prose, not machine-readable; ADR-0027 portability is asserted and no exit has been tested | not met (#15753) |
| Art. 30 — Contractual arrangements | ICT contracts with providers | Standard provider terms only; no DORA-specific contractual clauses | organisational (#15753) |

Legend: ✓ = met (control in place and evidenced); partial = some of the control exists, gaps remain;
not met = the control does not exist in code or process; organisational = a process or contractual
duty with no code artefact. The status is the honest position on 2026-11-11, not an aspiration; the
authoritative per-return statuses live in `openbank-libs/governance/regulatory-obligations.yaml`.
Articles 5–10 and 13–14 are marked ✓ as documented controls; they have not been re-audited in this revision.

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

- CNPG S3 WAL archiving — continuous database backup (RPO 15 min for T0 is a target; no RPO has been measured, see `dr-test-log.md`)
- Karpenter — node auto-replacement within ~30 min
- ArgoCD GitOps — infrastructure state recoverable from git in < 1 h
- Kafka replication factor 1 (sandbox), 3 (production) — message durability
- Per-service liveness + readiness probes — automatic pod restart on failure

---

## Risk register (summary)

| Risk ID | Risk description | Likelihood | Impact | Mitigating control | Residual |
|---------|-----------------|-----------|--------|-------------------|---------|
| R-01 | Database data loss | Low | Critical | CNPG WAL S3; 30-day CNPG retentionPolicy (35-day sandbox bucket lifecycle); PITR drilled for ledger-db only | Low |
| R-02 | Kafka message loss | Low | High | 7-day retention; idempotent producers | Low |
| R-03 | Secret compromise | Low | Critical | OpenBao; break-glass in AWS SM; mTLS | Low |
| R-04 | Image supply chain attack | Low | Critical | Cosign + KMS; SBOM; Kyverno verify | Low |
| R-05 | Single-AZ node failure | Medium | High | Karpenter replacement; ArgoCD resync | Medium |
| R-06 | Multi-region outage | Low | Critical | M6 not yet deployed; T0 RTO = 4 h only | High until M6 |
| R-07 | Third-party cloud failure | Low | Critical | Cloud-agnostic architecture (ADR-0027) | Medium |
| R-08 | Insider threat / misconfiguration | Low | High | OPA authz; Falco; required status checks and money-path gates on `main`; signed commits; independent LLM review. No second human approval is required (single maintainer, see GOVERNANCE.md), so a mistaken or malicious change by the one human with merge rights has no independent human check; the automated gates only catch defect classes someone has already encoded | Medium |

---

## Follow-up backlog (Art. 28/30)

- [ ] Vendor risk register for critical third-party ICT providers (AWS, Strimzi, CNPG, Keycloak)
- [ ] Contractual DORA Art. 30 clause in service agreements (relevant once commercially licensed)
- [ ] Multi-region architecture (Milestone M6) to achieve T0 RTO ≤ 30 min
