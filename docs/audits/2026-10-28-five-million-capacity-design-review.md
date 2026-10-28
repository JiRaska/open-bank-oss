# Five-million-client capacity design review and ADR grill

Date: 2026-10-28. Source: `b62d146f46cd2bafe354953fd831a2c7cd480670`.
Scope: Czech current accounts and CZK payments for one regulated bank; migration to outbox v2,
capacity, financial correctness, security, audit evidence and regulatory readiness.
[Proposed ADR-0356](../adr/0356-five-million-client-capacity-qualification.md).

**Verdict: suitable for a proposed qualification programme; not qualified for five million
clients or approved for bank production.** This is a source/design review, not an independent
audit opinion, penetration test, certification or legal opinion. No runtime capacity/failure
test or live configuration inventory was performed. Existing tests were inspected, not
re-executed as part of this documentation review. Repository checks validate documentation.

## 1. Corrections to the initial plan

| Earlier assumption | Critical correction |
|---|---|
| Five million clients implies a known capacity | Population, simultaneous activity, payment rate, history size, skew and latency are separate dimensions. No measured maximum is established. |
| v2 is ready, so switch immediately | The kernel exists; these three adopters still use v1. Qualify service wiring, transactional atomicity, stale-claim recovery, DEAD handling and migration locks before activation. |
| 2–3 days is the migration commitment | At best an optimistic candidate-code estimate. Tests, review, schema preparation, deployment and recovery evidence are separate critical-path work. |
| More pods will scale payments | Shared database writes, account contention, dispatch/consumer rates, pools and external rails can dominate. Measure the whole journey. |
| Two database instances imply no lost payments | Asynchronous replication does not establish zero acknowledged-write loss. Durability and fencing require explicit configuration and failure proof. |
| 5k TPS is the same as the daily model | An hour at 5k is 18M instructions; this is intentionally a stress test above the 10M/day forecast. |
| Add ledger partitioning | Journal partitioning already exists. Verify partitions, indexes, maintenance, history queries and growth rather than reimplement it. |
| Seven-day outbox retention solves storage | Cleanup must keep up with arrival rate and vacuum/WAL. Transport cleanup is not statutory audit/financial retention. |
| 90 days guarantees go-live | It is a gated programme with staffing/infrastructure dependencies. If topology must change, stop the production promise and decide that architecture separately. |

## 2. Why ledger/transaction/domestic are not on outbox v2

The public delivery record is inconsistent: [#11652](https://github.com/JiRaska/open-bank-oss/issues/11652)
originally lists ledger and transaction first in phase 3, while its
[2026-10-17 comment](https://github.com/JiRaska/open-bank-oss/issues/11652#issuecomment-6234758383)
explicitly excludes ledger, transaction and domestic-payment as previously agreed.
It does not record a technical incompatibility, accountable owner or return date.

Code confirms service-local v1 repositories for all three; the shared v2 implementation
exists. This is a partially completed adoption, not proof that transactions cannot use v2.
The conformance and claim-SQL gates remain **advisory** at this revision, so green CI does
not require completion. The separate SENT-retention gate is enforced and also covers v1.

Domestic's `DelegatedSpendBindingRepositoryImpl` claims reservation-finalization work and
calls `persistWithinCurrentTransaction` on the payment outbox. That is a distinct protocol
and a real boundary to preserve. It does not by itself justify exempting the ordinary
domestic outbox indefinitely. Transaction also has more than one persistence entry point.

Reuse #11652: reconcile its checklist against code, replace the indefinite exclusion with
three owned acceptance items, and record each deployment/evidence identity. Do not invent a
new ADR to re-decide v2, close the issue based on the kernel alone, or count retention as v2
adoption. This review does not edit issues or publish private conversation history.

## 3. Engineering findings and required proof

| Priority | Finding at the inspected revision | Required proof/action |
|---|---|---|
| P0 | No full-path 5M qualification evidence was established | Executable ADR-0356 profile, complete financial oracle and attested isolated runs under #7284/#3348 |
| P0 | Three critical repositories remain v1 | Under #11652, migrate ledger → transaction → domestic, one service at a time, preserving every business/outbox atomic write |
| P0 | v2's strongest ordering claims exceed the failure cases demonstrated by its small repository tests | Pause/resume stale owners, late acknowledgements, DEAD/replay, consumer dedupe/sequence and real interceptor tests; treat a failed proof as a release blocker |
| P0 | HA/DR design is not evidence of acknowledged-write durability or recovery | Quorum/fencing decision, AZ failure, data-loss oracle, representative restore and financial reconciliation; #8347 |
| P1 | Ledger date-range query materializes the range before `drop/take` | SQL keyset pagination with stable ordering, correct cursor contract and real large-data plans; preserve ADR-0055 |
| P1 | Ledger publish method has `@Bulkhead(1)`; v2 dispatch introduces concurrent sends | Review effective CDI interceptor concurrency, retries/timeouts and scheduler budget; direct repository tests cannot prove this wiring |
| P1 | Retention defaults cap one invocation at 1M rows/outbox | Measure event amplification, retention drain rate, oldest retained row, bloat, WAL and vacuum alongside peak traffic |
| P1 | Hot customer/omnibus accounts serialize or conflict | Skew and single-account sweep; measure lock/retry rate and p99, preserve spendability and double-entry invariants |
| P1 | Broker/worker/pool settings affect end-to-end throughput | Inventory actual effective deployment, independent failure domains, lag/poll rates and aggregate pool limits before sizing |
| P1 | Temporal worker rollout has its own retained-history constraints | Reuse ADR-0355/#13888; do not activate dispatcher/retention/schedulers on retained worker-only deployments |

Source locations:

- `openbank-{ledger-service,transaction-service,domestic-payment}/src/main/kotlin/.../persistence/repository/*OutboxRepositoryImpl.kt`.
- `openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/infrastructure/persistence/repository/DelegatedSpendBindingRepositoryImpl.kt`.
- `openbank-ledger-service/src/main/kotlin/com/openbank/ledger/infrastructure/persistence/repository/PanacheJournalRepository.kt`, `findByDateRange`.
- `openbank-ledger-service/src/main/kotlin/com/openbank/ledger/infrastructure/outbox/LedgerOutboxDispatcher.kt`.
- `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/persistence/outbox/{OutboxSql,AbstractPanacheOutboxRepository,OutboxSentRetentionJob}.kt`.
- `openbank-libs-testing/src/main/kotlin/com/openbank/libs/testing/outbox/OutboxRepositoryV2ConformanceIT.kt`.
- `openbank-libs-runtime/src/test/kotlin/com/openbank/libs/persistence/outbox/OutboxClaimPlanIT.kt`.
- `.github/gates/gates.yaml`: `outbox-conformance-it-present`, `outbox-claim-sql-ratchet`, `outbox-sent-retention`.

The current claim-plan fixture is substantially smaller than the proposed history/retention
envelope. Its plan/row-buffer assertion is useful regression evidence, not proof of 5M
capacity. A count query avoids payload materialization but is not constant-time merely
because it uses `count(*)`.

## 4. Security, audit, law and governance review

Status vocabulary: **design present** means a source-level mechanism/document exists;
**unverified** means operational effectiveness was not established; **open** means a required
decision/evidence package is missing from this review. These are not interchangeable with pass.

| Surface | Status | Required acceptance evidence | Accountable role |
|---|---|---|---|
| Financial integrity | Design present; capacity/failure effectiveness unverified | End-to-end journals/holds/idempotency/reversals oracle under concurrency, response loss, restore and replay | Payments + ledger owners |
| Authorization, SCA, service identities | Existing threat models; deployment unverified | Positive/negative authorization tests through real ingress and service calls; amount/payee binding, expiry/replay, least privilege; retain controls under overload | Security + payments |
| Outbox/replay operator actions | Partial migration; failure proof open | Atomic append, claim lifecycle, consumer dedupe, explicit DEAD/requeue policy and auditable operator approvals | Kernel + service owners |
| Supply chain/change management | Governance rules exist; release effectiveness not audited here | Exact image/source/SBOM attestations, dependency and vulnerability results, effective branch protection, two independent money-path approvals and applicable threat-model updates | Engineering + security |
| Backup, HA and DR | Design present; representative recovery unverified | Signed actual restore/failover results, recovered transaction boundary, secrets/access control recovery and business reconciliation | SRE + bank continuity owner |
| Evidence integrity | ADR-0273 exists; capacity evidence open | Trusted execution identity, immutable raw artifacts, provenance, missing/skipped/stale handling, retention/access review | QA + internal control |
| DORA operational resilience | Mapping only, not compliance approval | BIA, ownership, monitoring, incident response/reporting process, capacity testing and demonstrated recovery | Bank ICT risk owner |
| ICT outsourcing/service contract | Not inspected | Service scope, SLAs, locations/subcontractors, audit/access rights, incident cooperation, tested exit/data portability and contractual allocation of responsibilities | Bank legal + procurement |
| GDPR/bank secrecy | Bank-specific lawful processing not assessed | Data inventory/flows, lawful bases, controller/processor terms, minimization, access and deletion/retention/legal-hold rules, DPIA decision and transfers assessment | Bank DPO + legal |
| Financial/AML records and audit trail | Transport retention is not the legal policy | Approved record-class retention schedule, protected evidence store, completeness/tamper checks, retrieval and lawful disposal; backups and legal holds included | Compliance + accounting |
| CZ payment operations | External acceptance unverified | Actual rail participation/certification, cutoffs, value dates, return/reconciliation procedures, incident and customer dispute handling | Payments operations + legal |
| ISO/SOC/PCI or customer audit | No certification/opinion provided | Defined audit scope, independent assessor and evidence of control operation over its required period | Bank/vendor assurance owners |

**Legal basis checked against primary sources:** DORA engages recovery/testing and ICT-provider
governance; the bank remains responsible for its obligations. A supplier's obligations depend
on the actual service/contract and any applicable designation, not the mere existence of an
open-source repository. Articles 28/30 include exit planning and audit/access provisions.
[DORA](https://eur-lex.europa.eu/eli/reg/2022/2554/oj).

Capacity identification, optimization and monitoring are explicitly engaged by Article 9 of
the adopted ICT-risk RTS. This ADR can support that procedure; it cannot replace evidence of
operating it. [Regulation 2024/1774](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32024R1774).

GDPR requires purpose-sensitive retention, protection and appropriate security/recovery
measures. A pseudonymous production extract is not automatically anonymous; qualification
fixtures should be synthetic. DPO/legal must assess production processing and DPIA applicability.
[GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj).

Where SCA applies to remote electronic payments, the authentication code must bind the amount
and payee; load testing cannot waive this control. [SCA RTS, Article 5](https://eur-lex.europa.eu/eli/reg_del/2018/389/oj).
CNB bank-secrecy guidance covers directly or indirectly identifiable client relationships and
banking information; use current RS2026-05, which replaced RS2026-03.
[CNB RS2026-05](https://www.cnb.cz/cs/dohled-financni-trh/legislativni-zakladna/stanoviska-k-regulaci-financniho-trhu/RS2026-05).

**Audit-document defect:** ADR-0242 labels recovery-target-to-restore-completion duration as
RPO and first healthy pod as RTO completion. The former mixes data-loss and recovery-duration
measures; the latter is insufficient business recovery proof. Correct the measurement contract
and verify the actual workflow under existing #8347 before accepting a recovery report.
Do not infer that today's workflow necessarily implements that outdated prose.

No blanket legal retention period, mandatory TLPT for every bank, PCI exemption, regulator
approval or audit guarantee is inferred here. A complete production review needs the actual
legal entity, countries, rail participation, supplier model, contracts and deployment scope.

## 5. Prioritized 90-day delivery without duplicate issues

Issues below were read on 2026-10-28 and were open. Their historical progress paragraphs are
not treated as current runtime evidence. Proposed extensions here have not been posted.

| Order / days | Concrete exit | Existing issue / scope |
|---|---|---|
| P0 / 1–7 | Approve versioned workload, latency/failure boundaries, named owners, budget and isolated environment; draft financial oracle and evidence contract | #7284 execution; #3348 regression gate |
| P0 / 1–15 | Shared-kernel fault/concurrency proof, then ledger v2 candidate with atomicity/rollback; inspect all transaction/domestic append paths | #11652 |
| P0 / 8–30 | Qualify and activate transaction/domestic v2 in sequence, update threat models, record exact deployment/rollback evidence; preserve retained workers | #11652; dependency #13888 |
| P1 / 8–30 | Fix bounded ledger reads; measure pool, event amplification, purge/vacuum and hot-account limits; establish infrastructure baseline | Extend #7284 qualification prerequisites; create a narrowly scoped implementation issue only after checking matching work |
| P1 / 31–60 | Build full data profile, controlled scaling runs, 72h soak, concurrent scheduled jobs, failover/restore and replay; collect cost and bottleneck evidence | #7284, #8347; #13888 for fault/replay integration |
| P1 / 61–75 | Enforced evidence validation and negative controls; repeat complete qualification on final artifact/config; independent security/financial review | #3348; reuse provenance/quality work in #13448 |
| P0 go-live / 76–90 | Bank acceptance package: technical results, legal/contracts, operational ownership and training, DR/exit evidence, unresolved-risk decisions; otherwise explicit no-go | Bank onboarding acceptance; no matching legal/onboarding umbrella was established in this bounded issue check |

Planning assumption: four backend engineers, two performance/SRE engineers and one QA engineer,
with available bank security/legal/payments owners and funded isolated infrastructure. It is
not a staffing commitment. Run financial-path deployments sequentially; data generation,
legal scoping and test-harness work can proceed independently. Day 30/60/90 are go/no-go
checkpoints. Failed correctness, unproven durability or unavailable bank controls stop go-live.
If cells become necessary, write the separate financial-topology ADR and revise the schedule.

## 6. ADR grill

The full DIGEST and the directly adjacent decisions were reviewed before number allocation.

| Question | Verdict | Evidence |
|---|---|---|
| Q1: already decided? | New, narrow capacity qualification contract; no supersession | DIGEST; ADR-0173/0243/0273 boundaries and adjacency table below |
| Q2: decision or task? | Decision constrains topology and admissible capacity evidence; v2 adoption stays issue work | ADR-0052; ADR-0356 alternatives; #11652 |
| Q3: enforcement? | Planned capacity checker; no current enforcement claim | gates.yaml's advisory outbox gates; ADR-0356 D7 |
| Q4: falsifiable delivery? | Yes: missing profile/checker, missing/stale evidence or invariant failures cannot qualify | ADR-0356 Delivery check; present-day existence check intentionally fails |
| Q5: money/regulation/deadline? | Money-path review and threat models; legal engagement mapped; checkpoint 2027-01-26 | rules.yaml money_path_services; threat-model files; ADR-0356 Context/Compliance |

Direct adjacency and why a new scope remains:

| ADR | Existing decision retained | Why it does not already decide ADR-0356 |
|---|---|---|
| 0039 | Ledger financial authority | No 5M workload/qualification contract |
| 0052 | Issues own actionable work | Routing rule, not capacity architecture |
| 0055 | Bounded shared search contract | Does not qualify whole payment journeys |
| 0152 | One bank per deployment | Tenant boundary, not throughput evidence |
| 0159 / 0325 | CNPG HA and scheduling | Replica/drain policy does not prove acknowledged-write durability at load |
| 0173 | Capacity/headroom and sandbox budget | No full-path, history-aware 5M acceptance profile |
| 0186 | Single-region sandbox DR posture | Not production-bank risk acceptance |
| 0218 | Credit-specific SLOs | Different product/journeys |
| 0242 | Recovery drill evidence | Does not define capacity; measurement wording needs follow-up |
| 0243 | HTTP performance pilot | Read pilot is not authenticated banking stress/soak |
| 0273 | Test evidence provenance | Does not define this workload or financial oracle |
| 0327 | Shared outbox v2 | Adoption is a prerequisite; not bank capacity proof |
| 0329 | SENT-row retention | Does not size cleanup against 30M hypothetical daily events |
| 0346 | Shared-library delivery/provenance | Component quality is not complete bank throughput |
| 0355 | Retained Temporal workers | Preserves history compatibility, not capacity qualification |

**Post-draft adversarial verdict:** pass as a proposed decision with planned delivery.
Production qualification remains open. The document deliberately refuses unsupported claims
about unlimited scale, strict broker ordering, lossless asynchronous failover, universal audit
acceptance or a guaranteed 90-day go-live.
