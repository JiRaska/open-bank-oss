# Atomic four-eyes approvals

A configured four-eyes gate must obtain a one-time claim before invoking its operation.
`RedisApprovalStore` uses a single-key compare-and-set script for checker decisions and
execution claims. Concurrent requests cannot both acknowledge a transition. An expired
or deleted key is not recreated by a late write. The interceptor starts a new pending
approval if a previously valid record disappears before it can be claimed.

With `authz.four-eyes.enforce=true`, a missing ApprovalStore now returns 503 through the
shared infrastructure-error mapper. Wire the service's store before enabling this flag.
OPA authorization enforcement and four-eyes enforcement are separate settings; an OPA
allow result carrying the obligation alone does not prove that a service enforces it.

## Rollout and rollback

The record encoding and TTL convention are unchanged. Redis must permit EVAL and the
GET/SET commands used by its script. Replace all writers for a service while approval
writes are quiesced before reopening its decision and execution paths. An older writer
uses unconditional SET and invalidates the atomicity guarantee during mixed operation.

A binary rollback preserves readable data but restores the old concurrency risk. Do not
resume approval traffic with an old writer; retain a version with atomic transitions.
Do not clear approval keys to resolve conflicts. Reconcile a lost response against the
approval record and the business operation before requesting another approval.

An EXECUTED record means the interceptor claimed authorization. It does not establish
that the subsequent business operation committed: the approval store and business
database are separate transactions. An uncertain business result needs reconciliation.
Redis TTL is not durable audit retention. Each endpoint must bind the exact intended
operation to its approval resource; this store fix does not add request-body binding.

`SharedApprovalConcurrencyIT` executes the production store against real Redis and checks
concurrent checker decisions, concurrent consumption and eviction at the write boundary.
`AuthorizeInterceptorTest` verifies missing-store and disappearing-record refusal;
`RedisApprovalStoreTest` retains the shared maker/checker contract checks.
