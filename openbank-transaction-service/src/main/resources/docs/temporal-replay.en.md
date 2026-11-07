# Payment workflow replay compatibility

`PaymentWorkflowReplayTest` replays immutable, synthetic histories captured with
Temporal Java SDK 1.25.1 and 1.40.0 against the current `PaymentWorkflowImpl`. It uses the shared
production data converter and context-propagator configuration. Replay does not
execute payment activities or connect to a production Temporal server.

The corpus covers successful debit and incoming credit, hold rejection, journal
failure with and without a cover hold, a journal retry followed by success, and
exhausted terminal-write retries after a successful journal. A negative control
inserts a timer before the first activity and must fail with nondeterminism; an
unrelated exception cannot satisfy that control.

Histories live in `src/test/resources/temporal/payment/sdk-1.25.1/`, together with
their capture provenance. Never regenerate these files as part of normal tests or
an SDK upgrade. The explicit `PaymentHistoryExportTest` checks the requested capture SDK (1.25.1
by default, or 1.40.0 via `OPENBANK_PAYMENT_CAPTURE_SDK`), writes only new files under
`build/payment-history-export/sdk-VERSION/`, and substitutes every
external activity with a synthetic implementation. Capturing a new corpus requires
review of its source revision, identities and payloads before committing it.

Replay proves workflow command compatibility with the recorded histories. The
existing workflow unit tests and `PaymentWorkflowTerminalWriteIT` remain necessary
for business outcomes and real database/outbox atomicity. Worker Deployment routing,
retaining draining worker versions and rollback routing require separate real-server
tests; this corpus alone does not establish those properties.


## Measured reverse-compatibility boundary

Every captured SDK 1.40 payment history contains language SDK flags 1 and 3. The
previous SDK 1.25.1 rejects flag 3 before replaying the payment command sequence.
The separate `temporalLegacyReplay` Gradle task loads the old SDK, asserts the actual
loaded version, successfully replays the old corpus and requires the precise
`Unknown SDK flag:3` error for all seven modern fixtures. Missing files or unrelated
exceptions fail this test. The task is part of `check`.

This is an explicit **SDK downgrade prohibition**, not a successful rollback test.
Worker routing rollback is established separately by `PaymentWorkerVersioningIT`,
which retains both versions and proves that their pinned executions still complete.
