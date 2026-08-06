# Runbook 0010 — Measure what a default-deny NetworkPolicy would break

Status: Stage 1 of the #2691 staged rollout. The repo-side half ships with this
runbook and runs in CI; the cluster-side half is a one-line addon change that is
deliberately NOT applied here (it needs a manual substrate dispatch, ADR-0060).
Owner: Platform + Security
Related: #2691 (the staged rollout), ADR-0060 (VPC CNI network-policy agent),
ADR-0081 (policies are DERIVED from the declared GitOps edges),
`docs/strategy/04-security-baseline.md` Layer 1 (default-deny is Required).

## Why this is a measurement and not a PR

`openbank-infra/scripts/gen-network-policies.py` derives every ingress allow-list
from what it can see in gitops: Deployment/Rollout `env` URLs, Ingress backends,
Pod/ServiceMonitors, HTTPScaledObjects, Kafka bootstrap URLs. A cross-namespace
URL that exists only as a Kotlin `@ConfigProperty(defaultValue = …)` or only in a
service's `src/main/resources/application.yaml` produces **no edge at all**. A
blanket default-deny would therefore drop flows nobody has enumerated.

So the order is: measure, close the gap, then enforce.

## The finding that changes the design: the VPC CNI has no audit mode

Measured on the sandbox, from the live DaemonSet rather than from documentation:

```
kubectl -n kube-system get ds aws-node -o jsonpath='{.spec.template.spec.containers[*].args}'
```

```
aws-node          NETWORK_POLICY_ENFORCING_MODE=standard
aws-eks-nodeagent --enable-network-policy=true
                  --enable-policy-event-logs=false
                  --enable-cloudwatch-logs=false
                  --log-file=/var/log/aws-routed-eni/network-policy-agent.log
```

The agent has exactly two enforcement modes and neither is log-only:

* `standard` — a pod that **no** policy selects is fully reachable; a pod that
  **is** selected is default-deny for that direction. This is what runs today.
* `strict` — a pod is denied until its policies are reconciled. Stricter, not
  quieter.

There is no `audit`. **A "default-deny in audit mode on one namespace", as the
issue's stage 1 proposes, cannot be expressed on this CNI** — applying a
default-deny anywhere is enforcement, immediately, with no dry run.

Two consequences worth stating plainly, because both invert the intuition:

1. **Applying default-deny to a namespace changes nothing for pods that already
   have a policy.** They are already deny-by-default. It only affects pods that no
   policy selects — today that is every workload in the 24 namespaces with no
   policy, plus operator-managed pods (CNPG Postgres) everywhere.
2. Conversely, "this namespace has a policy" is not "this namespace is protected":
   the policy selects specific pods by label, and a co-tenant workload the
   generator never emitted a policy for is wide open.

## The instrument that does exist: policy event logs

The node agent can log a verdict per flow — `ACCEPT` or `DENY`, with source and
destination IP and port — and that costs nothing in enforcement terms because it
observes traffic under the policies already in force. It is off today.

The switch is the vpc-cni addon's `nodeAgent.enablePolicyEventLogs`. Confirmed
against the live addon's own schema, not guessed:

```
aws eks describe-addon-configuration --addon-name vpc-cni \
  --addon-version "$(aws eks describe-addon --cluster-name openbank-sandbox \
      --addon-name vpc-cni --query addon.addonVersion --output text)" \
  --query configurationSchema --output text | jq .definitions.NodeAgent.properties
```

`enablePolicyEventLogs` and `enableCloudWatchLogs` are both string-typed booleans
under `nodeAgent`. **Leave `enableCloudWatchLogs` at `false`**: the logs stay a
node-local file, which is $0, where CloudWatch ingest on a fleet this chatty is
the same shape of bill the cluster log groups were already trimmed for.

### Procedure

1. Set `nodeAgent.enablePolicyEventLogs = "true"` in the vpc-cni addon's
   `configuration_values` (`openbank-infra/aws/modules/eks/main.tf`) and apply via
   the manual substrate dispatch. This restarts the node agent; it does **not**
   change any verdict.
2. Let it run for a week of real traffic — including a month-end batch if one
   falls in the window, since the rarest flows are the ones the enumeration below
   cannot see.
3. Harvest per node:
   `kubectl -n kube-system exec ds/aws-node -c aws-eks-nodeagent -- \
      cat /var/log/aws-routed-eni/network-policy-agent.log`
4. Resolve each flow's source and destination IP to a pod/namespace and compare
   the observed `(src ns, dst pod, port)` set against the generated allow-lists.
   Anything observed and not derivable is a stage-2 gitops declaration.

**No parser for step 4 ships with this runbook, on purpose.** The log format
cannot be observed while the flag is off, and a parser validated only against an
invented fixture is precisely the unfalsified-probe failure this repo keeps
paying for. Write it against the first real sample.

## The repo-side half, which needs no cluster at all

`.github/scripts/check-network-policy-code-edges.py` enumerates every
cross-namespace `http://<svc>.<ns>.svc:<port>` that appears in service source but
**not** in the caller's gitops Deployment env — the exact set the generator cannot
see — and classifies each against the committed policies:

| status | meaning |
|---|---|
| `ADMITTED` | some other caller declared the same edge, so the allow-list happens to cover it. Works, but only for as long as that unrelated declaration does. |
| `DROPPED` | the callee pod is policy-selected and this caller is not admitted — dropped in the cluster **today**. |
| `LATENT` | the callee pod is selected by no policy, so it works until default-deny lands. |
| `NO-CALLEE` | gitops declares no Service of that name. Dead config, not a policy gap. |

It runs as an enforced gate with a ratchet: today's four entries are baselined in
`KNOWN_MISSING` with the reason each is there, a new one fails at PR time, and a
fixed one must be deleted from the baseline — so the debt cannot go quiet in
either direction.

The largest thing it cannot see is named in its own output: a cluster URL default
that lives in a **shared library** (`openbank-libs`, `openbank-libs-runtime`)
belongs to every service that depends on it, so its caller namespace is not a
property of the source tree at all. That set has to come from the flow logs.

## Stage 2 exit criteria — what has to be true before any default-deny

* the gate reports zero `DROPPED` and zero `LATENT` entries;
* a week of policy event logs has been harvested and every observed cross-namespace
  flow is derivable from a gitops declaration;
* the shared-library edges have been attributed to concrete caller namespaces from
  those logs;
* and only then, one namespace at a time — starting with a namespace whose
  workloads are already fully policy-selected, where default-deny is provably a
  no-op for existing pods.
