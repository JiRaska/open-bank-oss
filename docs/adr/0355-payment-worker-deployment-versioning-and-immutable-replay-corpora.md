---
date: 2026-10-25
decision-status: accepted
delivery-status: partial
authors: [OpenBank contributors]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, testing, architecture]
summary: "Pin payment workflows to immutable Temporal worker deployments, retain workers independently of HTTP rollouts, and gate changes with original SDK history replay. SDK rollback is not worker-routing rollback."
followup: "#13888 — environment rollout, drainage evidence and remaining Temporal services"
---

# ADR-0355 — Payment worker deployment versioning and immutable replay corpora

## Context

ADR-0101 colocates Temporal workers with API processes. An HTTP canary rollout does
not know whether a workflow still needs the old worker. The Java SDK 1.25.1 cannot
interpret all SDK flags written by 1.40.0, even when the payment workflow's business
commands remain unchanged. A dependency-only rolling upgrade therefore cannot
promise rollback to the previous application image.

## Decision

Start with transaction-service. Keep other services and libs-temporal's compile-only
SDK contract at 1.25.1. Payment runtime and its test environment use 1.40.0 together.
Preserve the shared Kotlin converter, trace propagation, metrics, task queue,
activity retry policy and payment command sequence.

Mark `PaymentWorkflowImpl.execute` PINNED. Require explicit versioning configuration
and an immutable full Git SHA or image digest before a real payment worker polls.
API replicas may disable their worker using the existing enablement switch. A new
SDK worker without versioning fails at startup, rather than competing with legacy
workers on an unversioned queue.

Run retained workers in distinct Deployments, with no API Service selector overlap.
The environment's existing pod template supplies credentials, TLS, policy mounts and
network destinations. The public renderer accepts that template and a digest-pinned
image; it never applies resources or stores environment values. Disable schedulers
and outbox dispatch/retention on retained workers; API replicas continue dispatching
outbox rows. Allow at least 60 seconds for termination; the worker waits up to
30 seconds before forcing local shutdown. Temporal retains unfinished tasks.

Capture immutable synthetic histories before upgrading the SDK. Replay both old and
new corpora on the new SDK, including a deliberately incompatible workflow as a
negative control. Keep the old SDK compatibility test isolated from the shipped
runtime and explicitly demonstrate the reverse-compatibility boundary. Never modify
history flags or regenerate old fixtures to manufacture compatibility.

Rollback changes routing for new starts. Existing pinned executions retain their
worker. Keep both images and Deployments until server-side drainage evidence permits
retirement. No automatic garbage collection is introduced in this pilot.

## Alternatives considered

- Fleet-wide unversioned SDK upgrade: rejected after synthetic histories demonstrated
  new SDK flags that the old runtime cannot read.
- Reuse HTTP Rollout replica retention: rejected because HTTP rollout completion says
  nothing about Temporal workflow drainage.
- Temporal Worker Controller: a viable fleet-scale follow-up, but no controller or
  its CRDs currently exist in this repository. The first pilot uses explicitly
  retained Deployments and does not assume controller behavior.

## Consequences

Versioned deployments add temporary replica cost and require an explicit deployment
role split. Initial migration is ordered: retain legacy workers, add versioned
workers, validate/ramp routing, then update API replicas with their worker disabled.
The new image must not be substituted directly into the existing mixed API/worker
Rollout. This is an intentional deployment-configuration breaking change.

The real-server test proves A/B routing and continued execution across rollback;
it does not prove a Kubernetes environment's secret mounts, permissions, network
policies or drainage status. The environment rollout remains tracked in #13888 and
must pass the existing money-path reviews. It must not use a successful replay test
as evidence that a newer SDK can be rolled back to 1.25.1.

## Compliance impact

This is an operational continuity and software-change control. It changes no payment
API, authentication decision, personal-data field or retention period. No regulatory
certification or new compliance claim is made; existing money-path review and
audit-evidence requirements continue to apply.

## References

- [Worker configuration](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning/configure-worker)
- [Unversioned migration](https://docs.temporal.io/production-deployment/worker-deployments/unversioned-to-versioned-migration)
- [Roll out and pin](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning/roll-out-and-pin)
- [Service rollout guide](../../openbank-transaction-service/src/main/resources/docs/temporal-worker-versioning.en.md)
