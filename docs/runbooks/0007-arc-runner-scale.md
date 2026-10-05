# Runbook 0007 — ARC runner capacity diagnosis

Linked alert: `FinOpsCiRunnerQueueHigh` in `prometheus-rules-finops.yaml`. It
compares `actions_runner_controller_ephemeral_runner_sets_assigned_runners`
with `actions_runner_controller_ephemeral_runner_sets_running_runners + 1` for
five minutes. This ratio signals pressure; it is not itself a count of queued
GitHub jobs or proof that a runner could not start.

1. Confirm that both metric families are present, then compare assigned and
   running runners using the labels returned by the current scrape. If either
   family is absent, this alert cannot evaluate; report a monitoring gap rather
   than treating an empty query as zero pressure. Look for assigned demand that
   persists while running capacity stays flat.

   ```promql
   actions_runner_controller_ephemeral_runner_sets_assigned_runners
   actions_runner_controller_ephemeral_runner_sets_running_runners
   ```

2. Inspect the matching ARC resources and runner pod events. Distinguish a
   pending pod that cannot schedule from a pod that starts but never registers,
   and from normal short-lived job bursts.

   ```bash
   kubectl get autoscalingrunnersets,ephemeralrunnersets -A
   kubectl get pods -A --field-selector=status.phase=Pending
   kubectl -n <namespace> describe ephemeralrunnerset <name>
   ```

3. Check the relevant hosted workflow's queued and running jobs to confirm
   user impact. Correlate pending runner pods with NodePool cap or node startup
   alerts before deciding whether the constraint is runner configuration or
   cluster capacity. [Runbook 0002](0002-karpenter-node-drain.md) covers the
   latter diagnosis.

Share the runner-set name, time window, metric ratio, and pending-job count
with the CI/platform owner. These steps do not change scaling or runner state.
