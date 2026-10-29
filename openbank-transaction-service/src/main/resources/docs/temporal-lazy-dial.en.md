# Lazy Temporal dialing

The payment write path is orchestrated by Temporal (ADR-0120 Phase 5), but Temporal is
not a boot dependency of this service. `TemporalClientProducer`'s lazy evaluates on the
first injection, and `WorkflowServiceStubs` connects eagerly — so constructor-injecting a
plain `WorkflowClient` dialled the Temporal frontend when the bean was created, even on
read paths that never start a workflow, and even with
`openbank.transaction.worker.enabled=false` (the registrar's injection point triggered the
same dial). On a host with no Temporal reachable the service then could not boot at all.

Both injection points therefore take `jakarta.inject.Provider<WorkflowClient>`:

- `TransactionService` dials on the first payment initiation (`workflowClient.get()` inside
  `initiateTransactionInternal`), never at bean creation.
- `PaymentWorkerRegistrar` dials only after passing its `worker.enabled` check, so a
  disabled worker never opens a connection.

Behavior is unchanged when Temporal is up: the first write pays the dial cost once, and
the producer caches the client from then on. The public six-argument constructor still
accepts a plain `WorkflowClient` (wrapping it in a `Provider`) so hand-constructed unit
tests keep their call sites.

## Why it matters operationally

The transaction read baseline lane (`perf/scenarios.yaml: transaction-read-baseline`)
boots this service on a throwaway postgres+redis stack with no Temporal frontend and
measures read latency there; before this change that configuration crashed the pod at
boot. The lazy dial is also what keeps the documented "disabled worker never dials out"
contract true: `worker.enabled=false` must mean no outbound Temporal connection, not
"connects but registers nothing".

A read path that accidentally re-introduces a direct `WorkflowClient` injection will boot
fine where Temporal is reachable and fail where it is not — the perf gate lane is the
canary that catches that regression.
