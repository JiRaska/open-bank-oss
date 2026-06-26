# Risk Register

> Last updated: 2026-05-26
> Status: **v0.1** — top 25 risks. Reviewed quarterly; per-incident triggers add new items.
> Methodology: Likelihood (1-5) × Impact (1-5) = Score. Inherent (pre-mitigation) and Residual (post-mitigation) recorded.
> Framework: DORA Art. 6 ICT risk register; ISO 31000 risk management.

## Scoring scale

**Likelihood:** 1 Rare · 2 Unlikely · 3 Possible · 4 Likely · 5 Almost certain
**Impact:** 1 Negligible · 2 Minor · 3 Moderate · 4 Major · 5 Catastrophic
**Score:** L × I (1-25). Risk appetite threshold: residual ≤ 8.

## Top risks

### R-001 Regulatory non-compliance (PSD2 / DORA / 5AMLD / GDPR)

- Category: Regulatory
- Inherent: L4 × I5 = **20**
- Residual: L2 × I4 = **8**
- Description: Reference implementation does not meet all regulatory requirements; operator inherits gaps and may be sanctioned.
- Mitigation: Compliance matrix (`07-compliance-matrix.md`), explicit out-of-scope disclaimers, operator checklist before go-live, annual third-party compliance audit.
- Owner: Maintainers + operator joint responsibility.
- Acceptance: Operator must complete pre-production compliance review.

### R-002 Customer money loss due to defect in payment service

- Category: Financial / operational
- Inherent: L4 × I5 = **20**
- Residual: L2 × I4 = **8**
- Description: Bug in payment, ledger, or saga causes double-debit, double-credit, or lost funds.
- Mitigation: Idempotency mandatory; outbox pattern; double-entry ledger invariants; saga compensation; reconciliation jobs (daily); transaction freeze procedure.
- Owner: Maintainers (defect prevention), operator (operational recovery).

### R-003 Supply-chain compromise (dependency, base image, build tooling)

- Category: Cybersecurity
- Inherent: L3 × I5 = **15**
- Residual: L2 × I4 = **8**
- Description: Malicious dependency or compromised CI infrastructure injects backdoor into a release.
- Mitigation: SBOM per release (Syft); cosign signing; SLSA L3 (target); Trivy + Dependency-Check; pinned hashes; trusted base images only; admission policy verifies signatures; quarterly supply-chain review.
- Owner: Maintainers.

### R-004 Maintainer burnout / bus factor of 1

- Category: Organisational
- Inherent: L4 × I5 = **20**
- Residual: L3 × I4 = **12** (above threshold — acknowledged)
- Description: Sole maintainer cannot sustain the project; community has no continuity plan.
- Mitigation: Open governance documented; CODEOWNERS allows additions; transparent decision log; courting co-maintainers as KPI; license is permissive so forks remain viable.
- Owner: Founder.
- Acceptance: This risk is **above appetite**. Acknowledged consciously; mitigated by openness rather than eliminated.

### R-005 Cryptographic break (TLS, AES, RSA — post-quantum)

- Category: Cybersecurity
- Inherent: L2 × I5 = **10**
- Residual: L1 × I5 = **5**
- Description: A primary cryptographic primitive is broken (e.g. by quantum compute or novel cryptanalysis), exposing customer data and payment integrity.
- Mitigation: Crypto-agility — algorithms abstracted behind interfaces; track NIST PQC (ML-KEM, ML-DSA); plan migration starting 2027.
- Owner: Maintainers (track + plan), operator (deploy).

### R-006 Insider threat (operator with privileged access)

- Category: Cybersecurity / personnel
- Inherent: L3 × I5 = **15**
- Residual: L2 × I3 = **6**
- Description: Privileged operator exfiltrates data, manipulates ledger, or sabotages availability.
- Mitigation: Just-in-time elevation; MFA; quarterly access review; tamper-evident audit log; segregation of duties; activity monitoring; background checks (operator responsibility).
- Owner: Operator.

### R-007 Data loss from primary database

- Category: Operational
- Inherent: L3 × I5 = **15**
- Residual: L1 × I3 = **3**
- Description: Primary Postgres cluster lost; backups corrupted; data unrecoverable.
- Mitigation: Multi-AZ synchronous replication; continuous WAL shipping; daily base backups; cross-region async; encrypted backups; quarterly restore drill; immutable backup snapshots (object-lock).
- Owner: Operator.

### R-008 Distributed Denial of Service (DDoS)

- Category: Cybersecurity / availability
- Inherent: L4 × I3 = **12**
- Residual: L2 × I2 = **4**
- Description: Volumetric or application-layer attack degrades availability.
- Mitigation: Edge DDoS protection (cloud provider or Cloudflare); WAF; rate limiting at multiple layers; CDN; autoscaling.
- Owner: Operator.

### R-009 Region-wide outage (cloud provider failure)

- Category: Operational
- Inherent: L2 × I5 = **10**
- Residual: L1 × I3 = **3** (post-M7 active-active)
- Description: Entire cloud region unavailable for hours/days.
- Mitigation: Multi-region deployment (active-passive M6, active-active M7); cloud-agnostic distribution allows multi-cloud DR; documented failover runbook; semi-annual DR drill.
- Owner: Operator.

### R-010 TPP / PSD2 API abuse

- Category: Regulatory / fraud
- Inherent: L3 × I3 = **9**
- Residual: L2 × I2 = **4**
- Description: Malicious or buggy TPP triggers excess consent calls, drains data, or initiates fraudulent payments.
- Mitigation: eIDAS QWAC/QSeal verification; per-consent quota; behavioural anomaly detection; FAPI 2.0 enforcement; consent revocation tooling.
- Owner: Maintainers (controls) + operator (operations).

### R-011 Account Takeover (ATO)

- Category: Fraud
- Inherent: L4 × I4 = **16**
- Residual: L2 × I3 = **6**
- Description: Phishing, credential stuffing, or SIM-swap leads to customer account compromise.
- Mitigation: Strong Customer Authentication (PSD2 Art. 97); WebAuthn / FIDO2 preferred; dynamic linking on payments; device binding; velocity checks; behavioural biometrics; customer alerting; account freeze tooling.
- Owner: Maintainers (controls) + operator (operations).

### R-012 Money laundering through the platform

- Category: Regulatory
- Inherent: L4 × I5 = **20**
- Residual: L3 × I3 = **9** (above threshold — acknowledged with operator dependency)
- Description: Criminals use the platform for layering / integration; operator faces 5AMLD/AMLD6 sanctions and reputational damage.
- Mitigation: Real-time AML monitoring (`openbank-aml-service`); sanctions screening; transaction monitoring rules engine; SAR/STR reporting workflow; PEP/sanctions list integration; UBO tracking. **Effectiveness depends heavily on operator's AML programme, scenarios, and analysts.**
- Owner: Operator (primary) + maintainers (tooling).

### R-013 GDPR breach (PII exposure)

- Category: Regulatory / privacy
- Inherent: L3 × I5 = **15**
- Residual: L2 × I3 = **6**
- Description: PII exposed through bug, misconfiguration, or breach; GDPR fines (up to 4% global revenue).
- Mitigation: Data classification; field-level encryption for sensitive fields; PII redaction in logs; right-to-erasure workflow (Art. 17); DPIA per significant change; breach notification within 72h (Art. 33); pseudonymisation by default in analytics.
- Owner: Operator (DPO) + maintainers (controls).

### R-014 Defect in SCA flow (PSD2 SCA bypass)

- Category: Regulatory / fraud
- Inherent: L3 × I5 = **15**
- Residual: L1 × I4 = **4**
- Description: SCA bypass or weakness allows unauthorised payment.
- Mitigation: Strict adherence to EBA RTS on SCA; dynamic linking enforced server-side; SCA exemption rules audited; FAPI 2.0 conformance test; pen-test of SCA flow annually.
- Owner: Maintainers + operator.

### R-015 Kafka unavailability cascading to payments

- Category: Operational
- Inherent: L3 × I4 = **12**
- Residual: L1 × I3 = **3**
- Description: Kafka cluster degraded; outbox publication halted; downstream sagas stuck.
- Mitigation: Multi-AZ Kafka with `min.insync.replicas=2`; outbox buffers writes in DB (publication can lag); circuit breakers + monitoring; backpressure handling; documented playbook to drain backlog.
- Owner: Operator.

### R-016 License violation by downstream user

- Category: Legal
- Inherent: L3 × I2 = **6**
- Residual: L2 × I1 = **2**
- Description: Downstream user violates MPL-2.0 (e.g. removes attribution).
- Mitigation: MPL-2.0 is widely understood; clear LICENSE + NOTICE; community vigilance; legal escalation only for material harm.
- Owner: Maintainers.

### R-017 Hostile fork / community fragmentation

- Category: Strategic
- Inherent: L2 × I3 = **6**
- Residual: L1 × I3 = **3**
- Description: A hostile fork drains community contributors and confuses adopters.
- Mitigation: Permissive licence + open governance reduces fork incentive; transparent decision process; explicit policy on accepting upstream contributions back.
- Owner: Maintainers.

### R-018 Vendor lock-in via cloud-native SaaS dependencies

- Category: Strategic
- Inherent: L3 × I3 = **9**
- Residual: L2 × I2 = **4**
- Description: Critical dependency on a single cloud SaaS (e.g. AWS-only Kafka, GCP-only KMS) limits operator choice.
- Mitigation: Cloud-agnostic distribution policy in Technology Radar; abstract cloud APIs behind interfaces; document portability per release.
- Owner: Maintainers.

### R-019 Unpatched critical CVE in dependency

- Category: Cybersecurity
- Inherent: L4 × I4 = **16**
- Residual: L2 × I3 = **6**
- Description: A critical CVE (CVSS ≥ 9.0) is unpatched in a deployed dependency, exploited.
- Mitigation: Dependabot + Renovate; Trivy scanning on every build; SLA — critical CVEs patched within 7 days, high within 30; emergency-patch process documented.
- Owner: Maintainers + operator.

### R-020 Data residency violation (GDPR Chapter V transfers)

- Category: Regulatory
- Inherent: L3 × I4 = **12**
- Residual: L2 × I3 = **6**
- Description: Customer data inadvertently leaves the EEA; Schrems II / cloud-act-style issues.
- Mitigation: Cloud-agnostic deployment allows EU-only hosting (Hetzner / OVH / EU sovereign cloud); data classification with residency tags; egress controls; SCC / TIA documentation; sovereign cloud option for sensitive operators.
- Owner: Operator (primary).

### R-021 Saga compensation failure leaves inconsistent state

- Category: Operational
- Inherent: L3 × I4 = **12**
- Residual: L1 × I3 = **3**
- Description: A saga step fails AND its compensation also fails; state is inconsistent.
- Mitigation: Idempotent compensations; persistent saga state with retry; alert on stuck sagas (> tier-specific threshold); manual remediation runbook; daily reconciliation between services and ledger.
- Owner: Maintainers (framework) + operator (ops).

### R-022 Subprocessor or third-party ICT provider concentration risk (DORA Art. 28-44)

- Category: Operational / regulatory
- Inherent: L3 × I4 = **12**
- Residual: L2 × I3 = **6**
- Description: Operator over-relies on a single critical ICT provider (e.g. single CSP); concentration risk under DORA.
- Mitigation: Multi-cloud capability; subprocessor register template; contractual exit clauses; viable exit plan documented.
- Owner: Operator.

### R-023 Reputational damage from public security incident

- Category: Strategic
- Inherent: L3 × I4 = **12**
- Residual: L2 × I3 = **6**
- Description: A publicly disclosed security incident damages trust in OpenBank as a platform.
- Mitigation: Responsible disclosure programme (SECURITY.md); rapid patching; transparent incident reports; clear comms; cosign + SBOM enable verifiable patching.
- Owner: Maintainers (project) + operator (deployment).

### R-024 AI agent (MCP) introduces unintended actions

- Category: Operational / cybersecurity
- Inherent: L3 × I4 = **12**
- Residual: L2 × I2 = **4**
- Description: AI agent connected via MCP triggers unintended state changes (mis-prompted, prompt injection, hallucinated calls).
- Mitigation: Agent operations behind explicit human approval for state-changing actions; read-only by default; full audit log; scope-limited tokens; never on payment write path; sandboxed environment.
- Owner: Operator (primary), maintainers (defaults).

### R-025 Performance regression released

- Category: Operational
- Inherent: L3 × I3 = **9**
- Residual: L2 × I2 = **4**
- Description: A change degrades latency or throughput, missing SLO under load.
- Mitigation: Nightly k6 load tests; CI perf gates on labelled PRs; release blocker on perf regression > 5%; canary deployments with auto-rollback on SLO violation.
- Owner: Maintainers (CI) + operator (deploy).

## Risks above appetite (residual > 8)

| ID | Risk | Residual | Rationale for acceptance |
|---|---|---|---|
| R-004 | Maintainer burnout / bus factor 1 | 12 | Inherent to early-stage OSS; mitigated by openness, not eliminable |
| R-012 | Money laundering | 9 | Heavily operator-dependent; reference implementation provides primitives, not analyst capability |

These risks are accepted **consciously** and revisited each quarter. Bringing them below appetite requires actions beyond pure software — co-maintainer recruitment for R-004, operator AML programme for R-012.

## Operational cadence

- **Weekly:** Triage new risk items raised by maintainers or operators
- **Quarterly:** Full register review; recompute scores; close stale items
- **After every Sev-1 / Sev-2 incident:** Add risk if not already covered; reassess scores
- **Annually:** Independent review (operator-funded for production deployments)

## Disclaimer

This is the maintainer's register for the **reference implementation**. Operators MUST maintain their own DORA Art. 6 risk register reflecting their specific deployment, scope, and customer base.
