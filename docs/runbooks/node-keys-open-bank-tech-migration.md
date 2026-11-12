# Node label and taint keys: `openbank.io/` → `open-bank.tech/`

The project does not own `openbank.io`. Every Kubernetes-object key moved to `open-bank.tech/` in one
PR, because changing a label or annotation on an object only requires that object to be re-applied.
Four keys could not move that way, because OpenTofu sets them **on nodes** and pods read them through
nodeSelectors and tolerations:

| Key | Kind | Set by | Read by |
|---|---|---|---|
| `openbank.io/pool` | node label | Karpenter NodePools (`sandbox-platform/main.tf`, `arc-runners.tf`), EKS bootstrap nodegroup (`modules/eks`) | `nodeSelector` in langfuse, customer-edge redis; ARC `runner_node_selector` |
| `openbank.io/stateful` | node taint | `stateful` NodePool | tolerations: Kyverno `stateful-on-demand-cel`, alloy, falco, argocd controller, langfuse, redis |
| `openbank.io/runner` | node taint | `runners-warm` / `runners-spot` NodePools | ARC `runner_tolerations` |
| `openbank.io/runner-tier` | node label | runner NodePools | ARC `runner_affinity` (preferred, not required) |

Rename the key on the node first and every pod that selects or tolerates it becomes unschedulable.
Rename it on the pods first and the same thing happens in the other direction. So the move takes
three stages. Each stage is safe on its own and can be left in place indefinitely.

## Stage 1: consumers accept both keys (gitops + one `tofu apply`, no node change)

- Every toleration of `openbank.io/stateful` gains a twin for `open-bank.tech/stateful`.
- The Kyverno policy adds whichever of the two the pod is missing.
- `check-stateful-not-on-spot.py --kyverno` requires both keys.
- `runner_tolerations` and the argocd controller (`tolerations[1]`) tolerate both keys.
- Node labels and taints stay unchanged, so nothing is rescheduled. The only effect is a rolling
  restart of the pods whose tolerations changed.

Done when: Argo is in sync, `tofu apply` for `sandbox-platform` has been run, and
`kubectl get pods -A -o json` shows that every pod tolerating the old key also tolerates the new
one.

## Stage 2: nodes carry the new keys (one `tofu apply`)

- NodePool template labels get `open-bank.tech/pool` and `open-bank.tech/runner-tier` alongside the
  old ones, and the bootstrap nodegroup gets `open-bank.tech/pool`.
- NodePool taints switch to the new keys. Pods already tolerate both keys after stage 1.
- **Cost:** Karpenter treats a changed NodePool template as drift and replaces the nodes. The
  `stateful` pool's drift budget is one node at a time with a 1h grace period, so the money-path
  databases fail over one replica at a time. Run this stage in a quiet window and watch CNPG
  primaries.
- Do labels and taints in the same apply, so each pool is replaced once.

Done when: `kubectl get nodes -L open-bank.tech/pool` shows the label on every node and no node
carries an `openbank.io/*` taint.

## Stage 3: consumers switch, old keys removed (gitops + `tofu apply`)

- `nodeSelector` and the ARC selector and affinity move to the `open-bank.tech/` labels.
- The old-key tolerations and the policy's old-key patch are removed. The gate then requires only
  the new key.
- The old labels are dropped from the NodePools. That is a second drift, so it can wait for natural
  node expiry if the replacement is unwanted.
- `git grep 'openbank\.io/'` must return nothing.
