# SEPA payment workflow histories

Recorded Temporal event histories of `SepaPaymentWorkflowImpl`, one per path through the workflow
(#15724). `SepaPaymentWorkflowReplayTest` replays every `*.json` here against the current workflow
with `WorkflowReplayer` and fails on any non-determinism. Run it before and after any change to the
workflow:

```
./gradlew :openbank-sepa-payment:test --tests '*SepaPaymentWorkflowReplayTest*'
```

## How they were produced

`SepaPaymentWorkflowHistoryRecorder` runs the shipped `SepaPaymentWorkflowImpl` and the shipped
`SepaPaymentActivitiesImpl` on Temporal's in-process test server (the `temporal-testing` version the
module depends on). Only the outbound ports are stubbed (repository, sanctions screening, AML case,
fraud scoring, scheme gateway, settlement) plus the Vert.x context bridge. The workflow code is not
copied or reimplemented. Payment ids, names and transaction ids are synthetic; the IBANs are the
published ISO 13616 example numbers; the worker identity is fixed so no host name is recorded.

## How to add one

1. Add a `Scenario` to `SepaPaymentWorkflowHistoryRecorder.scenarios()` and its name to
   `SepaPaymentWorkflowHistoryFixtures.EXPECTED_HISTORIES`.
2. Record it from the code that is on `main` BEFORE your workflow change:
   `SEPA_RECORD_TEMPORAL_HISTORIES=true ./gradlew :openbank-sepa-payment:test --tests '*SepaPaymentWorkflowHistoryRecorder*'`.
   Scenarios whose file already exists are reported as SKIPPED and never overwritten, so only the
   new one is written. Never delete a file to re-record it.
3. Commit the JSON in its own commit, then make the workflow change. Every committed history must
   still replay.

## The rule

**Never regenerate an existing history from changed code. A history is evidence about the code
that recorded it.** If an old history stops replaying, the change needs `Workflow.getVersion`,
not a new recording.
