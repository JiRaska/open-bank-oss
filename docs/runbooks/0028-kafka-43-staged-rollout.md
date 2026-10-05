# Kafka 4.3 staged rollout (#11415)

This sandbox has one combined KRaft controller and broker. A broker restart
interrupts Kafka traffic until that node is ready. Schedule a maintenance window
and use the normal GitOps PR, review, attestation, and deployment gates.

## Compatibility and rollback boundary

- Strimzi operator **1.2.0** supports both Kafka **4.2.0** and **4.3.1**:
  [Strimzi supported versions](https://strimzi.io/downloads/). Keep the
  operator at 1.2.0 throughout this rollout.
- The live baseline before the source change was Kafka **4.2.0**, KRaft
  metadataVersion **4.2-IV1**, and Kafka `Ready=True` (2026-10-05). Recheck
  immediately before deployment; a prior observation is not a deployment gate.
- Stage 1 changes only `spec.kafka.version` to **4.3.1**. It retains
  `metadataVersion: 4.2-IV1`. Before metadata is advanced, rollback is a
  reviewed GitOps revert to Kafka 4.2.0 while preserving 4.2-IV1, followed
  by a fresh readiness and client-path check. Stop if the running metadata
  differs from 4.2-IV1; a manifest revert is then insufficient.
- Kafka 4.3 introduces a metadata change. After finalizing metadata to **4.3**,
  downgrade to 4.2-IV1 is unsupported. Treat that as a separate, explicitly
  approved rollout with a verified data recovery plan, not an automatic
  rollback: [Kafka 4.3 upgrade guide](https://kafka.apache.org/43/getting-started/upgrade/).

## Stage 1: broker binary

1. Record the live Kafka CR `spec` and `status` versions and `Ready` condition,
   operator version/readiness, broker image version, node-pool readiness, and
   current alerts. Confirm all say 4.2.0 / 4.2-IV1 and operator 1.2.0 before
   the GitOps sync. Confirm storage recovery evidence and an owner for the
   maintenance window; do not interpret a green manifest check as a live proof.
2. Record a baseline for broker availability, under-replicated/offline
   partitions, consumer lag, DLQ growth, and the critical producer-to-consumer
   journeys used by payments and onboarding. Record only aggregate counts and
   status; keep credentials, event payloads, and internal endpoints out of PRs.
3. After the reviewed PR is deployed by the normal GitOps path, wait for the
   single broker pod and Kafka CR to be Ready. Confirm the **broker** image and
   `status.kafkaVersion` are 4.3.1 while `status.kafkaMetadataVersion` stays
   4.2-IV1. An exporter image tag alone is not broker evidence.
4. Observe the workload across a meaningful traffic window. Confirm client
   connectivity, critical producer-to-consumer journeys, bounded consumer lag,
   no sustained DLQ growth, and no new offline/under-replicated partitions.
   Capture the observation interval and outcomes before closing this stage.
5. If stage 1 fails while metadata remains 4.2-IV1, revert the broker-version
   GitOps change to 4.2.0 through normal review/deployment and repeat step 3–4.
   Escalate to the recovery owner if metadata changed or the old broker cannot
   read the data; do not force a metadata downgrade.

## Stage 2: metadata finalization

Only after stage 1 is stable and the rollback boundary has been reviewed,
prepare a **separate** change from `metadataVersion: 4.2-IV1` to `4.3`.
Confirm the exact target accepted by the live operator, the verified recovery
point and restore procedure, and the owner who accepts the irreversible step.
Deploy through the same GitOps gates, then recheck Kafka CR readiness, broker
version, finalized metadata version, client journeys, lag, partitions, and DLQs.
Do not merge stage 2 based on manifest validation alone.
