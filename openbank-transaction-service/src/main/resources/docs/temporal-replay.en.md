# Payment workflow replay compatibility

`PaymentWorkflowReplayTest` replays immutable, synthetic histories captured with
Temporal Java SDK 1.25.1 against the current `PaymentWorkflowImpl`. It uses the shared
production data converter and context-propagator configuration. Replay does not
execute payment activities or connect to a production Temporal server.

The corpus covers successful debit and incoming credit, hold rejection, journal
failure with and without a cover hold, a journal retry followed by success, and
exhausted terminal-write retries after a successful journal. A negative control
inserts a timer before the first activity and must fail with nondeterminism; an
unrelated exception cannot satisfy that control.

Histories live in `src/test/resources/temporal/payment/sdk-1.25.1/`, together with
their capture provenance. Never regenerate these files as part of normal tests or
an SDK upgrade. The explicit `PaymentHistoryExportTest` is restricted to SDK 1.25.1,
writes only new files under `build/payment-history-export/`, and substitutes every
external activity with a synthetic implementation. Capturing a new corpus requires
review of its source revision, identities and payloads before committing it.

Replay proves workflow command compatibility with the recorded histories. The
existing workflow unit tests and `PaymentWorkflowTerminalWriteIT` remain necessary
for business outcomes and real database/outbox atomicity. Worker Deployment routing,
retaining draining worker versions and rollback routing require separate real-server
tests; this corpus alone does not establish those properties.
