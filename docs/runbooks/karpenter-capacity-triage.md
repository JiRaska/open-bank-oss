# Karpenter churn and NodePool capacity alerts

This runbook covers `FinOpsKarpenterNodeChurnHigh`,
`FinOpsKarpenterChurnOutsideMaintenance`, and
`FinOpsKarpenterNodePoolNearCap`.

1. Record the NodePool, alert start time, measured terminations or CPU
   usage, and whether new pods are pending.
2. For churn, compare NodeClaim events and recent rollout or
   consolidation activity. Separate expected runner turnover from
   service-fleet termination and check whether affected pods rescheduled.
3. For a pool near its cap, compare declared pod CPU requests with
   actual demand and the configured pool limit. A pending pod may also
   have a scheduling constraint unrelated to the cap.
4. Escalate a service outage or repeated daytime terminations to the
   platform owner with the NodeClaim and pod event evidence. Verify that
   capacity and scheduling recover before clearing the incident.

Changing NodePool limits or disruption settings requires the normal
infrastructure review.
