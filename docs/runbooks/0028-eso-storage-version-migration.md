# External Secrets CRD storage version migration

Issue #11126 upgrades the External Secrets Operator (ESO) chart and GitOps manifests to
`external-secrets.io/v1`. The final storage step is separate: Kubernetes does not rewrite old
custom resources when a CRD changes its storage version. A CRD can therefore serve and store `v1`
while `status.storedVersions` still includes `v1beta1`. Do not remove the old version from the CRD
until every old object has been rewritten. See the [Kubernetes CRD versioning procedure][crd].

On Kubernetes 1.35, the built-in StorageVersionMigration controller is [beta and disabled by
default][svm-135]. This runbook uses the documented manual rewrite path and does not depend on
that controller. The script prints counts only; do not publish object listings, provider settings,
or kubectl diagnostics from a live cluster.

## Before the window

1. Confirm the ESO chart and CRDs are already installed, the ESO controller, webhook and cert
   controller are available, the GitOps application has no comparison/sync errors, and every
   ExternalSecret and store is Ready. Keep a recoverable platform backup. Do not start while an
   ESO rollout or GitOps sync is in progress.
2. Use a kubeconfig context you have checked independently. Pass that context by name to the
   script; it never selects the default context implicitly.
3. Review `status.storedVersions` and `spec.versions` for the four v1-promoted ESO CRDs:
   ExternalSecret, SecretStore, ClusterSecretStore and ClusterExternalSecret. The first three
   must exist. All targeted CRDs must serve and store `v1`, and `status.storedVersions` may
   contain only `v1` and `v1beta1`. PushSecret has a separate API lifecycle and is excluded.
   An unexpected version, scope, or CRD replacement stops the script.
4. Run the read-only inventory first:

   ```sh
   python3 openbank-infra/scripts/migrate-eso-stored-versions.py --context "$KUBE_CONTEXT"
   ```

   The count must match the current live inventory. The script inspects all four v1-promoted
   CRD kinds and every object belonging to a CRD that still lists `v1beta1`; it does not assume
   a fixed object count.

## Execute and verify

Run in a maintenance window with sufficient time for ESO reconciliation:

```sh
python3 openbank-infra/scripts/migrate-eso-stored-versions.py \
  --context "$KUBE_CONTEXT" --execute --finalize
```

For each old-version CRD the script reads all objects through the `v1` API, applies an optimistic
JSON patch to a harmless metadata annotation, and requires the object's `resourceVersion` to
advance. Kubernetes stores an updated object using the current `v1` storage version. It lists
objects again and requires every one to have this run's marker before it removes `v1beta1` from
the CRD's `status.storedVersions` subresource. The status patch tests the CRD UID, resourceVersion
and previous storedVersions, so a concurrent CRD change stops the operation. The script re-reads
the CRD after each status patch. A second successful run sees no pending CRDs and makes no writes.

After completion, independently confirm:

- Each targeted v1-promoted ESO CRD's storage version is `v1`, and every formerly pending CRD
  lists only `v1` in `status.storedVersions`.
- All ESO deployments remain available, all ExternalSecrets and stores remain Ready, and the
  GitOps application has no comparison or sync errors. Check again after at least one normal
  reconciliation interval; the script's metadata writes can trigger reconciliation.
- Existing Secret consumers remain healthy. Do not display or export Secret values to verify this.

Only then record completion of #11126. Script success alone is not operational completion.

## If a step fails

The script stops at the first failed object rewrite or verification and makes no **further** CRD
status changes. Keep ESO on a chart that serves `v1`, inspect the local kubectl error and current
CRD status, correct the cause, then rerun the read-only inventory and execute command. A rerun
rewrites remaining objects safely with a new marker. If an earlier CRD was already finalized,
it is skipped; partial completion does not justify restoring `v1beta1` in storedVersions.

Do not roll back to a chart that cannot serve `v1`. If the operator itself must be rolled back,
use a known compatible `v1`-serving chart and verify reconciliation. A full reversal to a
pre-`v1` cluster state requires the platform backup and its normal restore process; changing
`status.storedVersions` alone cannot convert stored data back.

[crd]: https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definition-versioning/#upgrade-existing-objects-to-a-new-stored-version
[svm-135]: https://v1-35.docs.kubernetes.io/docs/tasks/manage-kubernetes-objects/storage-version-migration/
