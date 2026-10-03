# Atomic four-eyes approvals

A configured four-eyes gate must obtain a one-time claim before invoking its operation.
`RedisApprovalStore` performs every check-and-write (create, checker decision, execution
claim) as one server-side Lua script, so the status and maker checks and the write are a
single atomic step. Concurrent requests cannot both acknowledge a transition. An expired
or deleted key is not recreated by a late write. The interceptor starts a new pending
approval if a previously valid record disappears before it can be claimed.

With `authz.four-eyes.enforce=true`, a missing ApprovalStore now returns 503 through the
shared infrastructure-error mapper. Wire the service's store before enabling this flag.
OPA authorization enforcement and four-eyes enforcement are separate settings; an OPA
allow result carrying the obligation alone does not prove that a service enforces it.

## Rollout and rollback

Records are stored as one hash per approval under a per-service namespace
(`approval-v2:<quarkus.application.name>:`), with a pending index and a per-maker index.
Records in the previous layout stay readable by id until their TTL ends and are moved on
first write; they carry no request binding and so never satisfy an intercepted request.
The TTL convention is unchanged. Redis must permit EVAL and the hash, sorted-set and key
commands used by the scripts. Replace all writers for a service while approval
writes are quiesced before reopening its decision and execution paths. An older writer
uses unconditional SET and invalidates the atomicity guarantee during mixed operation.

A binary rollback preserves readable data but restores the old concurrency risk. Do not
resume approval traffic with an old writer; retain a version with atomic transitions.
Do not clear approval keys to resolve conflicts. Reconcile a lost response against the
approval record and the business operation before requesting another approval.

An EXECUTED record means the interceptor claimed authorization. It does not establish
that the subsequent business operation committed: the approval store and business
database are separate transactions. An uncertain business result needs reconciliation.
Redis TTL is not durable audit retention. Each approval is bound to a fingerprint of
the request it was issued for; a retry with different arguments is not satisfied by it.

`RedisApprovalStoreIT` (libs-runtime) executes the production store against real Valkey: it binds
the shared maker/checker contract and checks concurrent checker decisions, concurrent
consumption (every racer held until all have read, before any writes) and eviction at the
write boundary; the race tests fail against a read-then-write store. Without Docker it is
skipped locally and fails in CI. `AuthorizeInterceptorTest` verifies missing-store and
disappearing-record refusal.
