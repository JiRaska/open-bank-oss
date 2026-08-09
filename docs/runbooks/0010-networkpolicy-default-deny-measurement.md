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

## The measurement that decides the order: coverage is PER POD

The last exit criterion above needs a number the repo cannot produce. Which pods
are already policy-selected is a property of the cluster, not of gitops: CNPG
Postgres instances are created by the CloudNativePG operator, so they carry no
Deployment for `gen-network-policies.py` to derive from and appear in no
component directory at all.

`openbank-infra/scripts/netpol-coverage.py` reports it. It is read-only
(`kubectl get pods/networkpolicies -o json`), costs nothing, and mutates nothing:

```
python3 openbank-infra/scripts/netpol-coverage.py
python3 openbank-infra/scripts/netpol-coverage.py --direction Egress
```

Measured 2026-08-06 against the sandbox, 400 running pods:

| | Ingress | Egress |
|---|---|---|
| pods selected by at least one policy | **109** | **5** |
| pods selected by none | **291** | **395** |

The 24-namespaces-without-a-policy figure understates this by an order of
magnitude, and understates it in the reassuring direction. 27% of running pods
are policy-selected for ingress; 1% for egress.

### The 40-namespace blocker: every database is the uncovered workload

In **40 namespaces the ONLY uncovered workload is `postgresql`**. That is one
finding with two faces, and both matter:

* **Today, without any default-deny**, every CNPG instance in the fleet accepts
  a connection from any pod in the cluster on 5432. The allow-lists protect the
  services and leave the data they guard reachable directly. This is the largest
  measured exposure in the issue and it does not need a rollout to fix — it needs
  a policy that does not exist.
* **Under a namespace default-deny** those same pods are the ones newly cut off,
  so applying default-deny to any of those 40 namespaces severs each service from
  its own database. The service does not error usefully: it times out in Flyway at
  boot and CrashLoops, and nothing in the stack trace says NetworkPolicy.

And the failure is worse than an outage, because it is not cleanly reversible by
the usual reflex. `components/temporal/temporal-network-policies.yaml` already
records the mechanism, discovered the hard way in the one namespace that has a
default-deny today: the CloudNativePG operator polls each instance manager on
:8000, a default-deny drops that poll, the cluster never reports Healthy, and the
**ArgoCD sync for that namespace hangs** on "waiting for healthy state" — so the
follow-up commit that would relax the policy cannot apply itself.

Any namespace default-deny therefore needs two carve-outs before it, per
namespace, not one:

* intra-namespace 5432 for the service → its own instances, and
* `cnpg-system` → the CNPG pods on 8000 (instance-manager status) and 9187
  (instance metrics),

which is exactly the shape of `temporal-cnpg-operator`. Generalising that from a
hand-written one-off into a derived policy for every CNPG cluster is the concrete
stage-2 work item, and it is worth doing **on its own merits** — it closes the
5432 exposure above whether or not default-deny ever lands.

### Egress is not stage 3, and the live proof is already in the cluster

The issue proposes default-deny-ingress and "zvážit egress". Ingress and egress
are not two settings of one change: an egress policy denies everything it does
not list **including same-namespace traffic**, so it takes the whole dependency
graph of a workload to be correct, not just its callers.

Four workloads have an egress policy today (`agent-service`, `copilot-service`,
`litellm`, `rum-gateway`). One of them is broken by it right now:
`copilot-service-egress-allow-list` has rules for DNS, LiteLLM, its Redis, IAM,
customer-edge and OTLP — and no rule for 5432. The pod CrashLoops in Flyway with
`SocketTimeoutException: Connect timed out` against a database in its own
namespace. One missing rule, one dead service, no diagnostic anywhere naming the
cause. (Fix in flight: #3885.) That is a single hand-written policy; a namespace
default-deny-egress is the same class of defect multiplied by the fleet.

What an egress allow-list has to carry, measured rather than assumed:

* DNS to `kube-system` — without it nothing resolves and every symptom is wrong;
* the EKS Pod Identity link-local credential endpoint. **87 pods across 50
  namespaces** carry `eks.amazonaws.com/pod-identity=enabled`; a NetworkPolicy
  cannot name it with a `namespaceSelector`, it needs an `ipBlock`, and without it
  every AWS API call (S3 backups, Secrets Manager, ECR) fails at credential
  fetch — a failure that looks like an IAM problem for as long as you let it;
* same-namespace 5432 (see above), Kafka mTLS 9093 in `messaging`, OIDC in `iam`,
  OTLP in `observability`, plus each service's cross-namespace REST callees;
* egress to the internet for the feeds that have one, which today is expressed as
  `ipBlock: 0.0.0.0/0` on 443 (`litellm-egress`) — i.e. the one dimension a
  default-deny-egress would most like to constrain is the one nothing constrains.

None of this is derivable from gitops env alone, which is the same hole stage 1
enumerated for ingress, in a direction where the blast radius is larger. **Egress
default-deny should be sequenced after ingress is complete fleet-wide, not
alongside it.**

### Which namespace goes first

Seven namespaces report zero uncovered pods — the only places where a
default-deny is provably a no-op for everything currently running:

`admin-ui` (1 pod), `analytics` (2), `argo-rollouts` (3), `customer-edge` (2),
`developer-portal` (2), `finrep` (1), `temporal` (6, already default-deny).

Recommended order, and the reasoning rather than just the pick:

1. **`finrep`** — one pod, no CNPG instance in the namespace, not money-path, its
   callers are already derived, and a regulatory report that fails is noticed and
   re-run rather than lost. Smallest blast radius that still proves the mechanism.
2. **`analytics`** then **`developer-portal`** — same argument, two pods each.
3. **`admin-ui`** only after the above: it is the operator's only window into the
   platform, so a mistake there also removes the tool you would diagnose it with.
4. **`customer-edge` last of the seven**, despite qualifying on the number: it is
   the public edge, and it is the caller in two of the three `DROPPED` edges stage
   1 found — a namespace with known-missing declarations is the wrong place to
   start, not the right one.

Everything else needs the CNPG carve-out first, by construction.

### What proves it is safe before the next namespace

Applying is a manual, owner-gated act (ADR-0060); nothing here applies anything.
The check after the first namespace, before the second:

1. `kubectl -n <ns> get pods` — every pod Ready, zero restarts added;
2. the ArgoCD Application for that namespace still reports `Synced/Healthy` — this
   is the CNPG deadlock probe, not a formality;
3. Prometheus has an unbroken scrape for the namespace's targets (a dropped scrape
   is the first symptom of an over-tight ingress policy, and it presents as
   `TargetDown`, i.e. as a false alarm about the workload rather than about the
   policy);
4. the namespace's own golden path exercised end-to-end from a real caller, not a
   port-forward — a port-forward enters via the API server and proves nothing
   about pod-to-pod policy;
5. `netpol-coverage.py --direction Ingress` still reports UNCOV=0 for it, i.e. no
   pod was added meanwhile that the default-deny is now silently severing.

Wait one full scheduling day before the next namespace. The flows this whole
runbook cannot see are the infrequent ones.

### What this measurement still does not see

Stated plainly, because a network change that overstates its own evidence is how
a platform goes dark:

* **it is a snapshot of running pods.** A CronJob, a scheduler that fires monthly
  (interest capitalization, year-close, FINREP/COREP render), a Job that only runs
  during a release, and any break-glass path taken by hand are all invisible to
  it — and every one of them is a flow a default-deny would sever with no warning
  until the day it matters;
* it answers "is this pod selected by a policy", never "is every flow this pod
  needs in that policy". A covered pod can still be missing a rule — that is
  precisely what `copilot-service` is;
* KEDA scale-to-zero means "no pod running" and "no such workload" look identical
  here, so a scaled-to-zero workload contributes nothing to either column;
* it reads the cluster, so it says nothing about a namespace that exists in gitops
  and has not synced.

The flow logs stage 1 enables are what close the first two. Until a week of them
has been harvested, the honest position on this issue is unchanged: **the
allow-lists are not complete enough to enable default-deny anywhere except,
possibly, the seven namespaces above — and the fleet-wide answer is still no.**
