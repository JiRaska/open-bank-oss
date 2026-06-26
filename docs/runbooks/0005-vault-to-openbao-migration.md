# Runbook 0005 — HashiCorp Vault 1.17.x → OpenBao migration

Status: Draft (needs a maintenance window — secrets plane)
Owner: Platform + Security
Related: ADR-0027 (cloud-agnostic OSS substrate), ADR-0079 (infra lifecycle), ADR-0054 (FinOps lifecycle)
**Supersedes: 0002 (Vault 1.17 → 1.19 upgrade)** — see "Why this instead of 0002".

## Why

Our secrets plane is **HashiCorp Vault 1.17.2** (now patched to 1.17.6; chart `vault` 0.28.1,
`openbank-infra/gitops/apps/vault.yaml`), which is **past End-of-Life**. Two ways out:

1. **Upgrade Vault** along the 1.17 → 1.18 → 1.19 → … → 2.x line (runbook 0002) — keeps us on
   **BUSL 1.1**, HashiCorp's source-available licence (since Aug 2023). Not OSI open-source;
   carries future-restriction risk for a self-hosting bank.
2. **Migrate to OpenBao** — the **Linux Foundation** fork of Vault under the original **MPL 2.0**
   licence (OSI open-source, vendor-neutral).

This runbook chooses **(2)**. It is the **same decision, same reasoning** as our just-completed
Redis → **Valkey** move (runbook 0004): drop the BUSL re-licence, take the LF-governed OSS fork.
It also fixes the EoL problem *better* — instead of repeatedly chasing Vault minors on a
shrinking-OSS licence, we land on an actively-maintained, MPL, LF-governed store and stay there.

### Why this instead of 0002

0002 (upgrade Vault to 1.19) keeps us on BUSL and still leaves a future 2.x major on the raft
store. Migrating to OpenBao removes the licence question entirely and is **no harder** than the
1.17→1.19 hops, because OpenBao is storage- and API-compatible (below). 0002 stays in the repo as
the fallback if the OpenBao migration is rejected.

## Compatibility — why this is low-friction

OpenBao forked from **Vault 1.15.x**, so for our 1.17 deployment everything we depend on carries over:

- **Storage:** raft, byte-compatible. OpenBao can **restore a Vault raft snapshot** directly.
- **Auto-unseal:** the `seal "awskms"` stanza is identical — OpenBao reuses the **same KMS key**
  (`alias/openbank-vault-unseal`), so a restarted/rescheduled pod still auto-unseals (no Shamir paste).
- **Auth + policies:** the kubernetes auth method (`mountPath: kubernetes`, role `eso`), KV v2
  mounts (`openbank/*`), and all policies migrate with the snapshot.
- **ESO:** external-secrets' `vault` provider speaks the OpenBao API unchanged — the
  `vault-kv` ClusterSecretStore only needs its `server:` URL repointed (or keep the Service name).
- **CLI:** the `bao` CLI mirrors `vault`; existing `vault` CLI calls also work against OpenBao.

What changes: the Helm chart (`hashicorp/vault` → `openbao/openbao`), the image
(`hashicorp/vault` → `openbao/openbao` 2.x), and the `vault.vault.svc` Service name (keep it, or
update ESO + every consumer that hardcodes it).

## Migration approach

**Approach A — blue/green snapshot-restore (recommended).** Stand OpenBao up beside Vault, restore
the snapshot, verify, cut ESO over, then decommission Vault. Instant rollback (Vault never stopped).

**Approach B — in-place storage adopt.** Stop Vault, point OpenBao at the same raft PVC. Less
storage, but Vault is down during the swap and rollback is messier. Use only if A's 2× storage is a problem.

This runbook uses **Approach A**.

## Pre-checks

- [ ] **Vault snapshot + KMS access rehearsed.** `vault operator raft snapshot save` works and the
      KMS unseal key is reachable from the OpenBao ServiceAccount (same EKS Pod Identity / IAM role).
- [ ] **OpenBao chart pinned + image allowed.** Add `openbao/openbao` repo, pick the 2.x chart;
      add `openbao/openbao:2.x` to the image set (third-party → not covered by the Kyverno
      `verify-openbank-image-signatures` policy, which only gates `openbank-*` ECR images).
- [ ] **ESO provider check.** Confirm the running external-secrets version supports the OpenBao/
      Vault-compatible API (it does — same `vault` provider).
- [ ] **Consumer inventory.** Anything hardcoding `vault.vault.svc` (ESO ClusterSecretStore, any
      sidecar). Plan to keep the Service name `vault` (least churn) **or** update all refs.
- [ ] **Staging dry-run.** Do the whole A flow in a throwaway namespace first.

## Steps (Approach A)

1. **Snapshot Vault (mandatory):**
   ```
   kubectl exec -n vault vault-0 -- vault operator raft snapshot save /tmp/vault.snap
   kubectl cp vault/vault-0:/tmp/vault.snap ./vault-$(date +%F).snap   # store off-cluster
   ```
2. **Deploy OpenBao** as a new ArgoCD app (`gitops/apps/openbao.yaml`, chart `openbao/openbao`),
   single-node raft, **the same `seal "awskms"` stanza + KMS key**, its own 5Gi gp3 PVC, its own
   ServiceAccount bound to the same Pod-Identity IAM role. Bring it up **uninitialised**.
3. **Restore the snapshot into OpenBao:**
   ```
   kubectl exec -n openbao openbao-0 -- bao operator raft snapshot restore -force /tmp/vault.snap
   ```
   It auto-unseals via KMS (same key) and comes up with all mounts/policies/auth.
4. **Verify OpenBao:** `bao status` (unsealed), `bao secrets list` (KV `openbank/`), `bao auth list`
   (kubernetes role `eso`), and read a known key (e.g. `openbank/glitchtip`).
5. **Cut ESO over:** repoint the `vault-kv` ClusterSecretStore `server:` to the OpenBao Service
   (or rename OpenBao's Service to `vault.vault.svc` for zero consumer churn). Force an ESO refresh;
   confirm every ExternalSecret re-syncs (`Ready`), no `SecretSyncedError`.
6. **Soak.** Keep Vault running and untouched for 24–48h as instant rollback.
7. **Decommission Vault:** remove `gitops/apps/vault.yaml`, let ArgoCD prune. Archive the final snapshot.

## Rollback

Until step 7, rollback is **repoint ESO back to Vault** (seconds) — Vault was never stopped and its
raft is unchanged. After step 7, restore the archived Vault snapshot into a fresh Vault. Because the
KMS key is shared, both stores unseal the same way.

## Risks & notes

- **Single point of secrets.** Do this in a window; ESO caches the last-synced k8s Secrets, so a
  brief store outage doesn't immediately break running pods — but new ExternalSecret syncs pause.
- **Divergence.** OpenBao is API-compatible today but diverges over time (own features). Pin to a
  known-good 2.x and track its releases on the same admin-UI lifecycle board.
- **Don't seed during migration.** The pending `openbank/glitchtip SECRET_KEY` / `openbank/alertmanager
  SLACK_WEBHOOK` seeds (read-only ESO can't write them) should be done **after** cutover, against OpenBao.

## Follow-ups

- Update the admin-UI infra registry: replace the `vault` component (eolProduct `vault`) with
  `openbao` so the Infrastructure card tracks OpenBao's lifecycle, not EoL Vault's.
- Swap the `build-push-*` cosign trust-root note if anything references Vault by name.
- Once stable, delete runbook 0002 (Vault upgrade) as superseded.
