---
date: 2026-10-28
decision-status: proposed
delivery-status: partial
authors: [OpenBank contributors]
supersedes: []
superseded-by: []
delivery-repos: []
followup: "#7284, #3348 — deliver the authenticated qualification profile, financial oracle and enforced evidence checks"
tags: [capacity, payments, resilience, testing]
summary: "Qualify five million Czech current-account clients against a versioned full-path workload and financial invariants; retain one logical ledger write authority until measured limits justify a separate topology decision."
---

# ADR-0356 — Five-million-client capacity qualification for Czech current accounts

## Context

ADR-0173 decides capacity monitoring and sandbox budgets; ADR-0243 decides an HTTP performance
pilot; ADR-0273 decides evidence provenance. None defines the full banking workload and
conditions for claiming capacity for five million clients. This ADR proposes that contract
and the topology choice it constrains. ADR-0327 already decides outbox v2: its remaining
migration is work under #11652, not a new architectural decision here. ADR-0039 remains the
financial authority model and ADR-0152 remains one regulated bank per deployment. No existing
ADR is superseded.

Source inspected: `b62d146f46cd2bafe354953fd831a2c7cd480670`, 2026-10-28. No live inventory,
capacity run, penetration test or bank legal approval was obtained in this review. Numbers
below are proposed qualification inputs, not measured capacity. The programme checkpoint is
**2027-01-26**, 90 days after this proposal; not a statutory deadline or guaranteed go-live.

## Decision

### D1 — Qualify a deployment and workload

Keep Kotlin/Quarkus, PostgreSQL, Kafka and Temporal, with one logical ledger write authority
per bank. Optimize bounded queries, dispatch, retention and resource allocation before
choosing another write topology. One authority does not mean one application replica:
database atomicity, uniqueness and concurrency controls remain authoritative.

A claim names source/image/config identities, topology, data profile, security configuration,
workload, measured limits and date. Qualification expires after 30 days or a material change
to those inputs, whichever comes first; this does not authorize deleting evidence. Service
benchmarks, row counts, HTTP 2xx counts and green CI summaries do not substitute for it.

### D2 — Initial profile: `cz-current-account-5m-v1`

These are proposed stress inputs. Product, payments and SRE owners must approve their
versioned executable realization before a qualifying run. Threshold changes require review
and cannot turn a failed run into a historical pass.

| Dimension | Profile and interpretation |
|---|---|
| Population | 5,000,000 synthetic clients; 10,000,000 accounts with documented active, dormant, closed and blocked distributions |
| Business volume | 10,000,000 unique originated instructions/day, two/client/day; internal transfers count once, not per leg; retries are not new instructions |
| Planning peak | 10M / 86,400 = 115.74 instructions/s average; 20x = 2,314.81/s; assumptions, not observations |
| Burst | 5,000 new valid instructions/s for 60 minutes, with 50,000 authorized reads/s concurrently; 18M instructions in that hour deliberately exceed the ordinary daily model |
| Payment mix | 40% internal transfers, 40% outbound CZK, 20% inbound CZK; retries, returns and reversals are additional, separately counted scenarios |
| Reads | 60% balance, 30% recent transaction history, 10% bounded account/search; report each endpoint separately, including deep cursors and cold caches |
| History | At least one model year: 3.65B instructions with corresponding journal lines, idempotency records and projections; report hot/archive placement, realistic payload sizes, bytes, indexes and WAL |
| Soak | 72 hours, 30M instructions total; three peaks per day at 2,315/s for 15 minutes, remaining rate adjusted to preserve 10M/day; include daily jobs, backups and retention |
| Skew | Repeat normal/fault runs with 20% of writes on 1% of customer accounts; separately sweep single-account/omnibus contention and report saturation |
| Dependencies | Real services, auth/policy, Postgres, Kafka and Temporal; isolated deterministic clearing simulation may replace external rails, but then the claim is platform-only |

Seeds must be deterministic, versioned and reconcilable. Sampled history helps engineering
but cannot qualify the full profile. The 50k read rate is an independent stress assumption
to validate with the first bank's channel model. Model user think time and interactive SCA
separately from server latency. Exercise valid authorization/SCA or a legitimate exemption;
never disable controls to obtain a performance result.

The interim campaign is limited to the existing shared sandbox and its current fixed
resources. It must not provision resources, deploy or roll out workloads, or change replicas,
HPA/KEDA, or NodePool settings. Collect a passive baseline before load, then run an existing
read-only baseline first with a verified least-privilege identity and owned, tainted test
fixtures. A later bounded write pilot may use only pre-existing synthetic fixtures after
ownership, taint and reconciliation are verified and its bounded run envelope is frozen
within the authorized fixture scope. Arbitrary mass writes and disabling authentication or policy are prohibited.
Numeric stop thresholds are run-specific approval inputs, not invented by this ADR; stop on
any approved envelope limit or the profile's listed safety/correctness signals. This interim
campaign does not execute the five-million-client population, burst, one-year history, soak or
fault profile, and it does not claim that all target scenarios ran.

Report observed shared-sandbox behavior at existing data and fixed resources separately from
the modeled five-million-population estimate and its assumptions. Queue, data-size and hot-key
projections remain model outputs unless measured at representative scale. If model inputs are
out of calibration or SLO uncertainty prevents a defensible estimate, record the result as
inconclusive, not qualified. Neither a bounded sandbox pilot nor an extrapolation qualifies the
full profile.

### D3 — Financial outcomes and bounded queues determine success

Measure from intended arrival, including waiting/retries, to avoid coordinated omission.
Use open arrival rates and prove generator headroom. Report offered, attempted, accepted,
rejected, booked and externally settled counts separately, with p50/p95/p99/max and worst
five-minute windows per journey.

Initial normal-run budgets: p99 <= 500ms durable instruction acceptance, p99 <= 2s internal
transfer booking, p99 <= 300ms per read class. External settlement follows a separately agreed
rail contract. Booking is not external settlement. A lost reply after commit is an unknown
client outcome until idempotent retry/reconciliation resolves it.

Normal and burst runs must pass the same latency, financial and queue budgets. At target
load, the failure fraction is <= 0.1% of intended offered valid unique instructions; its
numerator includes generator omissions, unresolved timeouts and capacity-driven 429/503
responses. Retried failed attempts remain separately visible even after eventual success.
Business rejections and negative-security tests have separate denominators. Every accepted ID must reconcile to its correct completed or
explicitly pending state and resolve within its journey deadline. Skips remain visible.

The oracle checks every test instruction: balanced journals per currency, one financial
effect per scoped idempotency key, correct reversal linkage, no double hold/release, no
spending above available funds/approved overdraft, and ledger agreement with rebuilt
projections after the convergence bound. Test concurrent debits, duplicate/delayed/out-of-order
events, lost replies and retries across booking dates. Broker-level delivery claims do not
establish exactly-once financial effects.

During a constant-rate plateau, unfinished work in the last ten-minute window must not
exceed the first ten-minute window by more than the plateau rate times one second; unresolved
age stays within journey deadlines. Compare only windows with equal arrival rate. Report
soak transitions separately rather than comparing peak and background windows. Observe all
nonterminal outbox states, delayed retries, DEAD rows,
consumer lag and workflow queues. Zero eligible rows is not zero outstanding work.
Unexpected DEAD rows or unexplained reconciliation differences fail qualification. After
arrivals stop, internal work reconciles within five minutes; simulated external work within
its declared bounded settlement window.

### D4 — Qualify failures separately

Exercise loss of an application replica, database primary, broker and availability zone,
plus latency, network partition and response loss. Describe independent failure domains.
Freeze each injection duration, severity and recovery criterion with SRE/payments approval
before qualification; absent bounds block acceptance of that scenario.
For AZ loss, target recovery within five minutes to >= 2,500 instructions/s and >= 25,000
reads/s for 60 minutes, meeting normal latency budgets after recovery. Report the entire
interruption/rejection interval in availability evidence. Above this degraded envelope,
bounded admission may reject before durable acceptance while preserving idempotency.
Backlog returns to its pre-fault age/count envelope within 30 minutes after restoration.

For local HA scenarios, **zero lost acknowledged financial writes** is a qualification
requirement, not a property inferred from two database pods. Record acknowledgement,
replication durability/quorum and failover fencing; test instruction, journal and hold stores
end to end. Losing the required durability quorum stops write acceptance instead of silently
weakening durability. Measure the latency/availability cost. Include Kafka replication,
minimum in-sync replicas, producer acknowledgements, Temporal persistence and database pools.

Regional DR requires a separate bank BIA/contract decision. ADR-0186's sandbox posture is
not production approval. Approve journey-specific RTO/RPO and prove restore at representative
volume, with financial reconciliation and access controls. RPO measures recoverable data loss
relative to disruption; RTO measures time to usable service. Restore duration is not RPO;
a healthy pod is not recovered banking. Reuse #8347 without replacing its existing targets.

### D5 — Finish outbox v2 without overstating guarantees

Under #11652, prioritize ledger, transaction, then domestic-payment after shared-kernel
qualification. Preserve business-write/outbox transaction boundaries, event identities,
headers, payload compatibility, taint and idempotency. Test all transaction persistence entry
points. Domestic's delegated-spend finalizer has a separate claim protocol: preserve it and
prove atomic outbox append instead of replacing every `SKIP LOCKED` with the generic base.

Activation requires:

- Real CDI/HTTP/database/Kafka tests with actual resilience interceptors and scheduler;
  align publish concurrency with the drain loop and scheduler interval with poll budget.
- Original dispatcher pauses beyond claim expiry, another reclaims, then the original resumes:
  delayed sends/acknowledgements/failures cannot corrupt financial state. Prove ownership/fencing
  or equivalent consumer sequence/idempotency handling. A claim-time row lock alone does not
  prove the entire send/ack lifetime.
- Explicit failed/DEAD/manual-replay head policy. DEAD is outside v2's head-blocking set;
  `(created_at,id)` is not automatically business revision or commit order. Do not infer
  arbitrary-replica strict wire ordering from the small existing conformance suite.
- Measured migration locks/index build on representative tables. Additive DDL is not inherently
  zero downtime. Follow ADR-0327's reviewed large-table preparation, inspect valid index
  definitions/plans and retain old indexes until evidence permits removal.
- One service at a time: quiesce old dispatchers before new claims. Concurrent v1/v2 publishers
  on one table are not qualified. Specify API/worker availability during that interval.
  Rollback retains additive schema/data but not v2 ordering/backoff guarantees; reconcile
  outstanding claims before another ownership change.
- Preserve ADR-0355 worker compatibility. Retained Temporal deployments keep schedulers,
  outbox dispatch and retention disabled; migration must not add a dispatcher there.

There is no assumed global switch for all repositories. Candidate code may be small;
activation depends on these proofs, not on a promised number of days.

### D6 — Remove measured bottlenecks before distributing financial writes

Prioritize SQL-level bounded pagination/indexes, pool/concurrency budgets, outbox drain/age,
retention/vacuum/WAL, hot-account contention, Kafka/Temporal consumers, and reconciliation,
statements/close competing with interactive traffic. Reuse existing journal partitioning.
Read replicas/projections can serve suitable reads but cannot authorize spending from stale
balances.

Measure amplification from traces/writes. An illustrative three ledger events/instruction
means 30M rows/day and 210M in seven days. Defaults of 5,000 purge rows x 200 batches cap one
retention invocation at 1M rows per outbox. More replicas do not replace a cleanup/vacuum plan.
Seven-day transport retention is not legal retention of financial/audit records (ADR-0329).

Consider cells/shards only if repeatable full-path runs still miss the target after query,
dispatch and infrastructure work, or cannot meet approved cost/failure budgets. The next ADR
must decide ownership/routing, cross-cell transfers, recovery, rebalancing, global idempotency,
liquidity and reconciliation. Hashing account IDs alone does not preserve atomic double-entry.
No finite benchmark establishes unlimited scale.

Report infrastructure, observability, backup and operational cost, cost/client/month and
cost/1,000 completed instructions at stated utilization. The bank-approved commercial ceiling
is currently unset: commercial qualification stays open. ADR-0173's sandbox caps are not a
production sizing promise.

### D7 — Evidence, enforcement and release authority

Implement the proposed `capacity-qualification` gate using
`.github/scripts/check-capacity-qualification.py`, a profile at
`perf/profiles/cz-current-account-5m-v1.yaml`, and an ADR-0273-compatible evidence manifest.
The proposed workload profile is now present; the full-path scenarios, full-profile financial
oracle, qualification checker and trusted-run evidence remain planned. A limited offline
internal-CZK-transfer pilot verifier is implemented in `perf/scripts/verify-payment-ledger.py`:
it reconciles independently extracted transaction/ledger rows, exact amount/base-currency legs
and synthetic journal marking. It does not establish balance/hold/reversal correctness,
trusted provenance or capacity. `perf/profiles/cz-current-account-5m-model.md` records sizing
arithmetic and calibration limits without asserting measured coefficients. The separate
`check-capacity-profile.py` validates profile consistency and the bounded sandbox/model
boundary only; it cannot qualify capacity. The profile distinguishes observations collected
on the existing fixed-resource sandbox from modeled five-million-population estimates. No
full-scale sandbox run or complete execution of the profile's target scenarios is claimed.
#7284 owns authenticated money-path execution; #3348 owns regression gating. Extend them instead of duplicating their trackers.

Reject absent/stale/failed/skipped required runs, wrong image/config/profile hashes, missing
invariants/scenarios, invalid attestations and unapproved threshold changes. Negative fixtures
prove each rejection. Trusted execution attests results; self-authored JSON saying `passed`
is insufficient. Register enforced qualification/release evidence checks before claiming
delivery. Expensive runs use an isolated lane; fast PR checks validate contracts rather than
pretending to run a five-million-client load on every PR.

Keep access-controlled raw artifacts with immutable identities, timestamps/clock uncertainty,
seed/dataset accounting, topology, effective config/versions, raw distributions, plans, queue
series, financial results and cost. Public summaries are sanitized. No customer data,
credentials, internal endpoints or recovery procedures enter this public repository.
Evidence freshness and evidence retention are separate policies.

Security, payments operations, SRE and the bank's accountable risk/compliance owner approve
the production package. Required human PR review and independent assessment remain. Neither
ADR nor checker certifies legal compliance, ISO/SOC/PCI scope or every auditor's acceptance.
Named owners and bank-specific contractual criteria remain prerequisites to assign.

## Alternatives considered

| Alternative | Benefit | Why not selected in this proposal |
|---|---|---|
| Shard into cells immediately | Independent write capacity/failure isolation | Adds cross-cell semantics before measuring the bottleneck; substantial migration/reconciliation cost |
| Replace database/runtime now | Different distributed-write primitives/throughput | No comparative full-path evidence; compatibility/recovery costs unquantified |
| Certify services and sum capacity | Cheap component diagnostics | Misses shared accounts, queues, auth, fan-out, history and recovery; serial-path capacities do not add |
| Chosen: retain topology, qualify the journey | Smallest semantic change; falsifiable result | May prove one write authority insufficient; qualification is allowed to fail |

## Consequences

**Positive:** reproducible claims; reuse of migration/evidence/DR work; financial correctness
remains a prerequisite at every load level.

**Negative:** realistic history and fault tests require substantial isolated infrastructure,
data-generation time and operations work. Ninety days may produce a defensible failed
qualification and topology follow-up instead of production readiness.

**Neutral:** `capacity-qualification` is planned, eventually enforced. At the inspected revision,
`outbox-conformance-it-present` and `outbox-claim-sql-ratchet` are advisory;
`outbox-sent-retention` is enforced. None proves this contract today. This ADR changes no running
configuration, gate mode, review rule or bank risk appetite.

## Delivery check

From the repository root:

```bash
# ready
test -f perf/profiles/cz-current-account-5m-v1.yaml && test -f .github/scripts/check-capacity-qualification.py && echo ready
```

Expected **today: nonzero**: the profile exists but the qualification checker and evidence
are not delivered, so status is partial. After implementation both files
must exist, `capacity-qualification` must have `mode: enforced`, and this planned CLI must
return zero only for complete, fresh, attested evidence:

```sh
python3 .github/scripts/check-capacity-qualification.py \
  --profile perf/profiles/cz-current-account-5m-v1.yaml \
  --evidence "$CAPACITY_EVIDENCE_MANIFEST"
```

Removing a required run, changing a financial total/profile hash or making evidence stale
must return nonzero. Green source checks without a qualifying run leave delivery incomplete.
External rail certification and bank sign-off remain separate even after technical success.

## Compliance impact

- PCI DSS: no PAN/card-processing workload is proposed; the bank must determine actual scope
  and shared-infrastructure dependencies. This grants no certification or exemption.
- DORA: supports the capacity procedure in Article 9 of Delegated Regulation (EU) 2024/1774.
  DORA Articles 11–12 and 24–25 engage recovery/testing; Articles 28 and 30 engage ICT-provider
  arrangements, audit/access rights and exit planning. This is one input to the financial
  entity's responsibilities, not complete compliance assurance.
- GDPR: Articles 5, 25 and 32 engage minimization, retention, security and recoverability;
  controller/processor arrangements and DPIA applicability need bank-specific assessment.
  Synthetic qualification does not establish lawfulness of production processing.
- PSD2: preserve authorization/SCA; where applicable, Article 5 of Delegated Regulation (EU)
  2018/389 requires binding to amount and payee. Simulation does not establish clearing
  participation or payment-service compliance.
- CNB: bank secrecy and supervisory evidence depend on the bank/operating model; see
  RS2026-05. Legal review must cover Czech payment, accounting, AML, retention and outsourcing
  obligations before go-live. No approval is claimed.

## References

- ADR-0039, ADR-0052, ADR-0055, ADR-0152, ADR-0159, ADR-0173, ADR-0186, ADR-0218,
  ADR-0242, ADR-0243, ADR-0273, ADR-0325, ADR-0327, ADR-0329, ADR-0346, ADR-0355.
- [Design review, priorities and ADR grill](../audits/2026-10-28-five-million-capacity-design-review.md).
- Existing delivery: [#11652](https://github.com/JiRaska/open-bank-oss/issues/11652),
  [#7284](https://github.com/JiRaska/open-bank-oss/issues/7284),
  [#3348](https://github.com/JiRaska/open-bank-oss/issues/3348),
  [#8347](https://github.com/JiRaska/open-bank-oss/issues/8347),
  [#13888](https://github.com/JiRaska/open-bank-oss/issues/13888),
  [#13448](https://github.com/JiRaska/open-bank-oss/issues/13448).
- [DORA](https://eur-lex.europa.eu/eli/reg/2022/2554/oj),
  [ICT risk RTS](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32024R1774),
  [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj),
  [SCA RTS](https://eur-lex.europa.eu/eli/reg_del/2018/389/oj),
  [CNB bank secrecy RS2026-05](https://www.cnb.cz/cs/dohled-financni-trh/legislativni-zakladna/stanoviska-k-regulaci-financniho-trhu/RS2026-05).
