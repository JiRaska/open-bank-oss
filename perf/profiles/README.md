# Capacity qualification profiles

Profiles are versioned workload contracts, not benchmark results or certifications. The
`cz-current-account-5m-v1.yaml` profile is **proposed** and records no observed capacity.
The `capacity-profile-consistency` CI gate validates arithmetic, YAML keys and scenario links;
it does not enforce capacity qualification. Product, payments operations, SRE, security and the bank's accountable
risk/compliance owner must approve its executable realization and pending assumptions before
a qualifying run. Its proposed targets must not be presented as measured SLOs.

The approved interim campaign is bounded to the existing shared sandbox and its current
fixed resources. It does not provision resources, deploy or roll out workloads, or change
replicas, HPA/KEDA or NodePool settings. Take a passive baseline first, then use an existing
read-only scenario with a verified least-privilege identity and owned, tainted fixtures. A
later bounded write pilot requires pre-existing synthetic fixtures, verified ownership/taint
and reconciliation, and an explicitly approved, frozen run envelope. Arbitrary mass writes
and disabling authentication or policy are prohibited. Numeric stop limits must be approved
for each run; this profile names stop conditions but invents no numeric thresholds. A run
must stop at any approved envelope limit or listed correctness/safety signal.

Record observed sandbox results only for the data and fixed resources actually present. Keep
the five-million-population estimate, assumptions and uncertainty in a separate modeled
section. Extrapolated queue, data-size and hot-key behavior is not observed evidence. If model
inputs are outside calibration or SLO uncertainty cannot support a defensible estimate, report
inconclusive rather than qualified. The current sandbox campaign does not execute the
five-million-client population, full burst, one-year history, soak or fault matrix; it does
not imply that all target scenarios ran or qualify the full profile.

The profile links the existing `money-path-write-benchmark`, `money-path-smoke` and
`security-abuse-smoke` entries in [`../scenarios.yaml`](../scenarios.yaml), with their definitions
under [`../k6/`](../k6/). The write benchmark remains local-only because it mutates account and
ledger state; do not redirect it to the shared sandbox. These scenarios are useful component
baselines or negative-security checks; none
covers the full five-million-client workload, financial oracle, fault matrix or soak. The profile
therefore leaves `qualification_scenario_ids` empty until full-path executable scenarios are
defined and reviewed. Do not add invented scenario IDs or count the current scenarios as coverage.

A future qualification evidence package must bind a trusted workflow run and attempt to the
profile digest, source commit, immutable application and k6 image digests, effective configuration,
topology/failure domains, deterministic dataset seed and generator version. It must retain access-
controlled raw artifacts and sanitized public summaries. Include raw latency distributions and
worst five-minute windows; offered, attempted, accepted, rejected, booked and settled counts;
per-instruction financial-oracle/reconciliation results; queue counts, ages, lag and DEAD rows;
database plans, IO, WAL and resource series; cost at stated utilization; observation timestamps and
clock uncertainty; and issuer-verified execution attestation. A manifest field saying `passed` is
not attestation. Failed, skipped, absent, stale, incomplete or unattested required evidence cannot
qualify.

The 60-minute burst is mandatory and must meet the normal latency budgets, valid-unique-instruction
error budget, financial oracle and queue bound. Its error denominator is intended arrivals of valid
unique instructions; generator omissions, unresolved client timeouts and capacity-driven 429/503s
are included as failures. Retries stay attached to the original instruction, are not new denominator
units, and must remain separately observable, including timeout resolution. Compare queue growth only
between 10-minute plateaus at the same constant intended-arrival rate; report ramps, rate changes and
fault transitions separately and still enforce journey deadlines and reconciliation there.

Required financial checks cover balanced journals per currency, one effect per scoped idempotency
key, reversal linkage, single hold/release effects, available funds/approved overdraft, projection
rebuild agreement, concurrent/reordered/retried events and resolution of every accepted instruction.
The fault package must show replica, database-primary, broker, availability-zone, latency, partition,
post-commit response-loss and quorum-loss behavior, including zero lost acknowledged financial
writes. Bank and SRE owners must approve and freeze every injection's severity, duration, boundaries
and recovery boundary before qualification; those values are pending, and no implicit defaults
qualify a run. Regional DR is out of scope for platform-only qualification but required for bank
production qualification after a separate bank BIA/contract decision and approved targets. This profile does not
replace ADR-0327 outbox work or #8347's existing DR targets.

Run the contract check with `python3 .github/scripts/check-capacity-profile.py --root .`
and its rejection fixtures with `python3 .github/scripts/check-capacity-profile.py --self-test`.
These commands validate the proposed model; they do not run traffic or validate run attestations.

The [fixed-sandbox sizing model](cz-current-account-5m-model.md) records demand arithmetic,
source-level outbox/retention bounds, sensitivity examples and the required calibration method.
Its unknown coefficients and capacity conclusion remain explicit.
The [internal CZK pilot oracle](../scripts/verify-payment-ledger.md) verifies a limited set of
financial invariants from offline database snapshots; it is not a full-profile oracle or attestation.
The [evidence-integrity bundle verifier](capacity-evidence.md) binds file hashes to a signed
workflow identity and exact GitHub run attempt. Its issuer workflow and real cryptographic
end-to-end validation remain pending; an integrity result never qualifies capacity.

The ADR proposes a 30-day qualification freshness limit; evidence retention remains a separate
policy. No profile or passing CI check alone establishes bank approval, commercial qualification,
external-rail settlement, regulatory compliance or production readiness.
