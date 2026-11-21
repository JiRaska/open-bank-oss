# Trace-contract testing helpers — openbank-libs-testing

`com.openbank.libs.testing.trace.TraceContract` is the shared assertion DSL the
per-service trace-contract integration tests (`*TraceContractIT.kt`, one per
service, wired into the Test Intelligence trace-evidence matrix) build their
evidence on. It wraps an OpenTelemetry in-memory exporter and states, per
contract, which spans one operation must produce and which trace they must share.

## `requiresSameTrace` vs `requiresSameTraceAs`

`requiresSameTrace(vararg spanNames)` measures the whole recorder: every same-named
span observed so far must share one trace id. That is right for a dedicated test
double (a topic, a table, an endpoint) nothing else touches.

Real services generate sibling traffic that lands on the same tables and operations
a contract anchors on — a test fixture's own REST journey produces `UPDATE <table>`
spans in its request traces long before the delivery under test runs.
`requiresSameTraceAs(referenceSpan, vararg spanNames)` anchors the assertion to the
trace of the ONE span named `referenceSpan` instead: the named spans are required to
exist in THAT trace (they may occur in other traces too). The reference must be
unambiguous in the recorder — for a Kafka contract, a dedicated topic so exactly one
consume span exists.

Both helpers count as one successful assertion on the contract and refuse an empty
span list at construction.
