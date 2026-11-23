---
date: 2026-11-18
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, testing, ci]
summary: "A trace contract is a hermetic profile contract (pinned sampler, isolated recorder), and any module carrying one declares an explicit 2g test JVM heap, enforced by the trace-contract-heap ratchet gate."
---

# ADR-0388 — Trace contract evidence conventions and the test-JVM heap budget

## Context

The Test Intelligence evidence matrix (ADR-0273 applicability) lists `trace` as a
layer, and the 2026-11 trace-evidence sweep added a `*TraceContractIT` to the 21
services whose gap report row was empty. Getting those tests green surfaced three
recurring, expensive defects that prose alone did not prevent:

1. **The sampler is part of the deployment, not the contract.** Several tests
   observed `span names only: []` even though the SDK was present: the default
   parent-based sampler legitimately drops the root span when the incoming
   traceparent is unsampled, so the recorder has nothing to read. The hermetic fix
   is a test-profile pin `quarkus.otel.traces.sampler=always_on`; the root cause of
   the *default* sampler dropping freshly-started roots on some modules is still
   unexplained (issue #16768).
2. **A trace contract is a second Quarkus boot.** `TraceContractProfile` exists to
   give the contract its own application instance (its own `RecordingSpanExporter`,
   dispatcher off). The module's test fork therefore carries two augmentations and
   boots where the rest of the suite carries one. At Gradle's default 512 MiB test
   heap this measured as `OutOfMemoryError` — loyalty, copilot and referral all
   reddened CI on 2026-11-18, and referral's suite *hung 45 minutes* when the
   instrumentation agent could no longer allocate and the Kafka testcontainer never
   started. Every one of these read as an infra flake. Every single-class run was
   green; only the full suite reproduced it.
3. **The heap fix trips the docs gate.** `build.gradle.kts` is a production input
   for `check-service-docs-freshness`, so the one-line heap fix also requires an
   authored note under `src/main/resources/docs/` — which is how it should be, but
   it means the heap budget is exactly the kind of operational knowledge that
   belongs written down next to the resilience bounds.

## Decision

We will treat a trace contract as a **hermetic profile contract** with three
conventions, two of them machine-enforced:

1. Every `*TraceContractIT` runs under a `TraceContractProfile` subclass and pins
   `quarkus.otel.traces.sampler=always_on` in its overrides. Sampling policy is the
   deployment's concern; the contract asserts causality (`requiresSameTrace`), which
   is only decidable when spans are unconditionally recorded.
2. Every module whose test tree contains a `*TraceContractIT` declares an explicit
   test heap — `tasks.test { maxHeapSize = "2g" }` (the statement-service
   convention), unless a local measurement justifies otherwise — **enforced by the
   `trace-contract-heap` ratchet gate**. Six modules are baselined under issue
   #16805; a new occurrence fails CI, and a covered baseline entry is reported so
   the list shrinks in one direction only.
3. The heap budget is documented in the module's authored docs (the
   `check-service-docs-freshness` gate already enforces the edit; the content is a
   "Test JVM heap budget" section next to the resilience bounds, stating what was
   measured and how to re-measure).

## Alternatives considered

- **Fleet-wide default heap in the shared convention plugin** — set `maxHeapSize`
  in `openbank.quarkus-service` for every module. Rejected: it spends memory in the
  ~60 modules whose suite never boots a second profile, hides the cost of adding
  one, and a blanket raise measured nothing. The existing per-module pattern
  (statement, kyb, account, case-coordinator) is the cheaper, already-reviewed
  shape; the gate makes forgetting impossible instead.
- **Retry-on-OOM in CI** — treat the OOM as a flake and re-run. Rejected: the
  referral incident shows the failure mode is a 45-minute *hang*, not a clean
  red; retries burn runner minutes and teach the fleet to distrust a real signal.
- **Do nothing beyond the four fixed modules** — rely on the docs note. Rejected:
  prose is not a control (the `@ConfigProperty` and runBlocking precedents); without
  a gate the nineteenth trace contract repeats the OOM discovery.
- **Flat fail on all six un-declared modules** — redden main until the sweep lands.
  Rejected: the remediation is a deliberate per-module edit paired with a docs
  note, which is issue #16805's sweep; a ratchet keeps CI green meanwhile.

<!-- Required (enforced). Reconstruct, never invent: if no alternative was genuinely
     considered, say exactly that instead of manufacturing a plausible rejected option. -->

## Consequences

**Positive**
- The trace-contract OOM class is un-repeatable: a gate fails the PR that
  introduces it, with the fix stated in the failure message.
- The sampler pin and the profile isolation are written down where the next
  contract author looks, ending the `observed span names only: []` debugging
  cycle (issue #16768 remains open for the deployment-side root cause).
- The 45-minute referral hang has a named cause and a named prevention; "infra
  flake" is no longer the default explanation for full-suite-only failures.

**Negative**
- One more enforced gate in a 373-gate manifest; the self-test keeps it honest but
  the fixed CI cost is real (~0.3 s run + self-test on input changes).
- Modules gain a 2g test fork where 512 MiB sufficed; on the shared runners the
  memory ceiling, not the heap flag, is the binding constraint.

**Neutral**
- The baseline under #16805 is a small standing debt with a defined burn-down;
  it is visible in the gate's summary line, not hidden in a spreadsheet.

## Compliance impact

- PCI DSS: not applicable — internal test-infrastructure decision; no cardholder-data flow changes.
- DORA:    not applicable — no ICT risk register entry; testing resilience supports but does not alter a registered control.
- GDPR:    not applicable — no personal-data processing change.
- PSD2:    not applicable — no interface or consent behaviour change.
- CNB:     not applicable — no reporting or prudential calculation change.

## References

- `openbank-libs-testing/.../TraceContractProfile.kt` — the profile whose isolation motivates the second boot.
- `.github/scripts/check-trace-contract-heap.py` — the ratchet gate (with inline self-test).
- Issue #16768 — default sampler dropping root spans; the pin's root cause is still open.
- Issue #16805 — the fleet sweep burning down the six baselined modules.
- CI runs 44293166069 (loyalty), 44293183518 (copilot), 44302517242 (referral) — the measured OOMs.
- ADR-0273 — test-layer applicability, whose trace row this sweep fills.
