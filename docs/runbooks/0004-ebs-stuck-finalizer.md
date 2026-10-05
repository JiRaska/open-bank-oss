# Runbook 0004 — EBS attachment and PVC diagnosis

Linked alerts: `FinOpsEbsAttachmentFailing` and `FinOpsPvcStuck` in
`prometheus-rules-finops.yaml`. The first reads
`kube_event_count{reason="FailedAttachVolume",type="Warning"}` over 10 minutes;
the second reads `kube_persistentvolumeclaim_status_phase{phase=~"Pending|Lost"}`.
Both identify symptoms, not proof that a force-detach or finalizer removal is
safe. The commands below are read-only.

1. Use the alert's namespace and object labels to find the affected pod and
   claim. Read events to distinguish a missing claim, provisioning failure,
   topology mismatch, multi-attach conflict, and a slow attachment.

   ```bash
   kubectl -n <namespace> get pods,pvc -o wide
   kubectl -n <namespace> describe pvc <claim>
   kubectl -n <namespace> describe pod <pod>
   ```

2. If the claim is bound, identify the PV, CSI driver, and the Kubernetes
   VolumeAttachment. Check attachment state and the target node without
   exposing a volume identifier in a public issue or log.

   ```bash
   kubectl -n <namespace> get pvc <claim> -o jsonpath='{.spec.volumeName}{"\n"}'
   kubectl get pv <pv> -o yaml
   kubectl get volumeattachments -o wide
   kubectl describe volumeattachment <attachment>
   ```

3. Compare the pod's scheduled node with the VolumeAttachment target. A
   `Multi-Attach` event may reflect a previous node still releasing the volume;
   `Pending` can instead mean no volume was provisioned. Check the node's pod
   startup alerts and [runbook 0026](0026-stuck-node-and-argocd-drift.md) if
   several pods are stuck on one node.

Record the duration, event reason, claim/PV relationship, and affected
workloads for the storage owner. Do not remove a VolumeAttachment finalizer or
detach a volume based only on these alerts; a running database may still use it.
