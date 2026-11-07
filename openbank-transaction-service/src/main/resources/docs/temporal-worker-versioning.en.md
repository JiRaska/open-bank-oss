# Versioned payment workers

ADR-0355 changes the deployment contract. **Do not replace the existing mixed
API/worker image with SDK 1.40 before preparing retained workers.** New SDK workers
must enable versioning and provide an immutable identity; otherwise they refuse to
start. API-only replicas use `OPENBANK_TRANSACTION_WORKER_ENABLED=false`.

## Build and verify

```sh
./gradlew :openbank-transaction-service:test --tests '*PaymentWorkflowReplayTest' \
  --tests '*PaymentWorkflowImplTest' --tests '*PaymentWorkerVersioning*'
./gradlew :openbank-transaction-service:temporalLegacyReplay
python3 -m unittest discover -s openbank-transaction-service/deploy -p 'test_*.py'
```

The real-server test needs Docker. Its pinned Temporal CLI 1.8.2 image embeds server
1.31.2; the deployment manifest currently uses server 1.31.4. The test retains two
factories, holds an activity across each routing change and verifies which worker
executes subsequent journal and terminal activities. It also exercises the shared
producer's actual gRPC/metrics branch. Run an environment-specific probe on the
exact deployed server before routing real payments.

## Required worker configuration

| Variable | Meaning |
| --- | --- |
| `OPENBANK_TRANSACTION_WORKER_ENABLED` | `true` on retained workers, `false` on API replicas |
| `OPENBANK_TRANSACTION_WORKER_VERSIONING_ENABLED` | `true` on SDK 1.40 workers |
| `OPENBANK_TRANSACTION_WORKER_VERSIONING_DEPLOYMENT_NAME` | Stable deployment name, `payment-execution` |
| `OPENBANK_TRANSACTION_WORKER_VERSIONING_BUILD_ID` | Full Git SHA or 64-character image digest; never a mutable tag |

The renderer uses the image digest as the build ID. Each digest receives its own
Deployment and matching ingress policy; it never rewrites the previous Deployment.
Do not change code or image under an existing build ID. A worker name collision must
be resolved explicitly, not by replacing a retained image.

## Initial migration

1. Record the current immutable SDK 1.25.1 image and the environment's reviewed API
   pod template. Preserve that original image and its compatible configuration.
   Render a legacy retained worker with `deploy/render-retained-worker.py --legacy
   --source ENVIRONMENT_ROLLOUT --image LEGACY_IMAGE_AT_DIGEST`.
2. Review and deploy the rendered Deployment and NetworkPolicy through the existing
   private environment/GitOps process. Confirm legacy workflow and activity pollers.
   Retain the original API deployment during this preparation.
3. Render a second worker from the new SDK 1.40 image, omitting `--legacy`. Confirm
   its deployment version and both task-queue pollers in Temporal before routing.
   This worker has no API Service, disables scheduled/outbox work and uses the
   existing database, policy, TLS and secret references. Check environment egress
   permissions and management scraping explicitly.
4. Verify the immutable original histories replay, then use Temporal deployment
   ramping and inspect execution version information. Set the new version Current
   only after the pilot succeeds. Existing unversioned histories can migrate on
   their next task, so their compatibility is required too. Do not send invented
   wake-up signals: PaymentWorkflow has no such signal handler.
5. Update API replicas with `OPENBANK_TRANSACTION_WORKER_ENABLED=false` before
   introducing the SDK 1.40 API image. Their outbox dispatcher remains enabled.
   HTTP rollout percentages are independent from Temporal routing percentages.
6. Keep the legacy worker until all relevant executions have migrated or completed
   and server-side task/poller evidence confirms it is no longer required.

Generated YAML contains environment configuration: retain it in the private
configuration store, not in this public repository. The renderer deliberately has
no apply or garbage-collection command. Money-path review and deployment gates
still apply; #13888 tracks the environment rollout evidence.

## Subsequent rollout and rollback

Deploy the next immutable version alongside the previous one. Use the Temporal
worker-deployment CLI to ramp or change Current, with the intended namespace and
deployment explicitly selected. Verify both workflow and activity pollers; do not
bypass missing-task-queue or missing-poller checks.

Rollback routes **new starts** to the retained known-good version. Executions pinned
to the newer version still need its workers. Preserve them until they drain, or
prepare a separately reviewed compatible recovery version. Resetting workflows or
moving histories to an incompatible SDK is not part of this rollback procedure.

Before deleting a retained Deployment, establish server-side drainage, no remaining
pinned executions and no pending activities for that version. Zero HTTP traffic,
zero recent logs and a completed Argo Rollout do not establish drainage. Keep image
artifacts for the history retention/recovery window even after scaling workers down.
