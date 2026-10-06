# NAT and fleet egress alerts

This runbook covers `FinOpsNatEgressSpikeAbsolute`,
`FinOpsNatEgressSpikeRelative`, and `FinOpsFleetEgressHigh`.

1. Record the alert name, affected namespace, start time, and measured rate.
   Compare the current rate with the previous healthy window. A short batch
   may cross a relative threshold without creating a sustained cost increase.
2. For a namespace alert, compare the affected namespace with other
   namespaces. For a fleet alert, compare nodes. A uniform rise across nodes
   suggests a shared agent or a destination retry loop; a concentrated rise
   suggests a particular workload. The fleet metric is a proxy for total
   egress and does not distinguish cross-AZ from internet traffic.
3. Check recent deploys and the health of destinations the affected workload
   sends to. A rejecting destination can make clients retry and multiply
   egress. Keep the original alert and destination error evidence together.
4. Ask the workload owner to confirm expected traffic before changing
   thresholds or traffic policy. Escalate unexplained traffic to the security
   owner. Record the observed cause and whether the rate returned to baseline.

These alerts provide a signal to investigate. They do not authorize changing
network policy, shutting down a workload, or modifying cloud resources.
