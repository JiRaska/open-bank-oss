# ADR-0060 — CI applies the platform OpenTofu (OIDC, manual-dispatch)

Date: 2026-06-02
Status: Accepted
Delivery-Status: Shipped
Author(s): Jiri Raska

**Delivery note (updated 2026-06-30):**
- **Phase 1 (manual-dispatch)** — ✅ Shipped: GitHub OIDC → IAM, two roles split by blast radius (plan/apply), manual-dispatch safety valve implemented and tested; live since 2026-06.
- **Environment-gated upgrade** — ⬜ Deferred: reviewer-gated GitHub Environment requires a plan upgrade (GitHub Pro/Team); tracked as follow-up.

## Context

The substrate/platform is OpenTofu (ADR-0027: OpenTofu + ArgoCD), and governance
mandates *derive from code → enforce in CI → ... → apply* (ADR-0029). But the last
step was missing: **nothing applied the platform tofu on merge.** ArgoCD owns the
in-cluster *application* manifests (`gitops/`), not the tofu substrate (the EKS
cluster, NodePools, IRSA/Pod-Identity, the ARC runner scale sets). No CI job ran
`tofu apply` either.

The cost surfaced while shipping the ADR-0053 stuck-runner reaper: it merged to
`main` but stayed **un-applied** until a human ran a manual `tofu apply`. "As-code
without applied" lets `main` and the live account silently diverge — a
change-management/DORA gap and a drift foot-gun.

The tension: closing this means giving CI an IAM principal powerful enough to apply
the *entire* env (IAM, EKS, EC2/Karpenter, ECR, in-cluster helm/RBAC) — effectively
account- and cluster-admin. For a banking-grade repo that trust must be airtight.

**Plan constraint.** The stronger design — apply auto-runs on merge but pauses on a
reviewer-gated GitHub **Environment** — is not available here: environment
protection rules (required reviewers) on a **private** repo require GitHub
Pro/Team/Enterprise; on the current Free plan the API returns
`422 — billing plan doesn't support required reviewers`. So this ADR adopts the
**manual-dispatch** variant that is safe on the current plan, and records the
Environment-gated upgrade as a documented follow-up.

## Decision

**A `Platform OpenTofu` GitHub Actions workflow runs `tofu plan` on every PR/push
touching `openbank-infra/aws/envs/**`, and a manually-dispatched `tofu apply`
(`workflow_dispatch`) on `main`.** Auth is **GitHub OIDC → IAM** (no static AWS
keys). Safety rests on the *trust scope*, not on a trimmed permission list:

1. **Two roles, split by blast radius.**
   - `openbank-ci-tofu-plan` — `ReadOnlyAccess`, assumable from any ref/PR of this
     repo. Runs `tofu plan -lock=false` on the credential-free **batch** pool; the
     plan is published to the job summary (the pre-merge preview).
   - `openbank-ci-tofu-apply` — `AdministratorAccess`, assumable **only** by the
     exact workflow file on main, pinned via the OIDC **`job_workflow_ref`** claim
     (`.../platform-tofu.yml@refs/heads/main`). A different workflow merged to main
     — or any PR — cannot assume it.

2. **Manual dispatch as the human gate.** The apply job is `workflow_dispatch`
   only: a human with write access presses "Run workflow" on `main` after reading
   the PR's `tofu plan` preview. Apply never runs unattended on a push. This is
   weaker than a reviewer-gated Environment (no enforced second approver, no
   enforced branch policy on the deployment), but it is the strongest gate
   available without paid environment protection, and strictly better than the
   status quo (a laptop `tofu apply` with personal admin creds).

3. **AdministratorAccess on the apply role, deliberately.** A hand-trimmed policy
   would fail applies whenever a new resource type is introduced and train
   operators to widen it reflexively. The boundary is the `job_workflow_ref` pin +
   manual dispatch, which is auditable and hard to misconfigure. (Scope is one
   sandbox account; revisit per-env least-privilege if a prod account is onboarded.)

4. **TOCTOU-safe apply.** The apply job runs `tofu plan -out=tfplan` then
   `tofu apply tfplan` in the same job; applying a saved plan re-locks and rejects
   a stale plan if state moved. No plan artifact is shipped between jobs — a saved
   tfplan can contain sensitive values and an artifact would expose them.

5. **Cluster access via EKS Access Entries.** The cluster runs in Access-Entry auth
   mode (no `aws-auth` configmap); each role gets an access entry — ClusterAdmin for
   apply, AdminView for plan (so the helm provider can read release Secrets on
   refresh).

6. **State safety.** S3 backend with `use_lockfile = true`; apply uses
   `-lock-timeout=300s` so a concurrent apply serialises on the lock.

7. **Runner routing (ADR-0053).** plan → `openbank-batch`; apply → `openbank-deploy`.

8. **Bootstrap.** The roles/OIDC-provider/access-entries are themselves in the env,
   so the first apply is run manually; CI maintains them thereafter.

## Alternatives considered

- **Keep manual laptop `tofu apply`.** Rejected: the drift gap this closes; already
  forgotten once (the reaper).
- **Auto-apply on merge, reviewer-gated Environment.** *Preferred but blocked* by
  the Free-plan limitation above; adopt on upgrade (the apply role trust would move
  from `job_workflow_ref` to `environment:<name>`).
- **Auto-apply on merge, no gate.** Rejected: an admin-capable apply with no human
  step on every push is too sharp for a banking repo.
- **Trimmed least-privilege apply policy.** Deferred (future per-env option):
  brittle against new resource types; weaker boundary than the workflow-ref pin.
- **ArgoCD applies it.** Rejected: ArgoCD reconciles Kubernetes manifests, not the
  AWS substrate.

## Consequences

**Positive**
- Merged platform changes are applied from CI (one dispatch), auditable as a run;
  no laptop creds. PR authors get a `tofu plan` preview.
- No static AWS credentials in GitHub; short-lived OIDC only. The admin role is
  reachable only from one pinned workflow file on main.

**Negative**
- The apply is manual (a dispatch), not automatic on merge — a deliberate step
  remains, though in CI not on a laptop. Mitigated by being a one-click run.
- No enforced second-approver/branch-policy on the apply (Environment would add
  it) — tracked as a follow-up gated on a plan upgrade.
- A powerful IAM role exists; its safety depends on the `job_workflow_ref` pin and
  GitHub not mis-issuing the claim.

## Compliance impact

- **DORA:** removes a manual laptop step; the change pipeline becomes an auditable
  CI run (merge → reviewed PR plan → dispatched apply → run record).
- **Supply-chain (ADR-0030):** no long-lived cloud keys; OIDC trust pinned to one
  workflow file; actions pinned by SHA.
- PCI/GDPR/PSD2: unchanged (no CDE/personal data; sandbox account).

## Follow-ups (tracked, issue JiRaska/open-bank#282)

1. **Reviewer-gated Environment** for apply once the repo is on GitHub Pro/Team
   (move the apply trust to `environment:platform-apply`, switch apply back to
   `push` + `environment:`). Stronger than manual dispatch.
2. `main` direct-push hardening if desired (a `require pull request` ruleset rule;
   note the existing `main-protection` ruleset already requires status checks +
   up-to-date).

## References

- ADR-0027 — substrate (OpenTofu + ArgoCD).
- ADR-0029 — governance as code (derive → enforce → **apply**).
- ADR-0030 — supply-chain (no static secrets; pinned actions).
- ADR-0053 — ephemeral ARC runners (runner routing; the reaper whose un-applied
  merge surfaced this gap).
- `openbank-infra/aws/envs/sandbox-platform/ci-tofu-apply.tf` — roles, OIDC, access entries.
- `.github/workflows/platform-tofu.yml` — the plan/apply pipeline.
- Issue JiRaska/open-bank#274 — the follow-up this realizes; JiRaska/open-bank#282 — the Environment/plan upgrade tail.
