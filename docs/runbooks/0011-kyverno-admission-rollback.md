# Runbook 0011 — Kyverno admission rollback (supply-chain verification policies)

**Scope:** the `Enforce` supply-chain ClusterPolicy rejects a *legitimate* workload at
admission and you need it running now.

| policy | what it requires | file |
| --- | --- | --- |
| `verify-openbank-image-sbom-attestation-cel` (`ImageValidatingPolicy`) | a valid Cosign signature **and** a `cyclonedx` attestation on the same image, both against the KMS public key — one policy | `openbank-infra/gitops/components/kyverno/cel-image-validating-sbom-attestation.yaml` |

**Until 2026-09-13 these were two policies** (`verify-openbank-image-signatures` held the signature
check). They were folded into one because Kyverno v1.12.5 keeps a single verification status per
image and lets whichever verify policy is evaluated last decide it for all of them — so an Audit
policy could deny and an Enforce failure could be admitted, depending on Go map order (#9805 item
4). A third policy, `verify-openbank-image-slsa-provenance` (Audit), was removed from admission for
the same reason. Both return as separate policies after the Kyverno upgrade that scopes verdicts
per policy (>= v1.19.0).

It matches **`kind: Pod`** with `imageReferences: 265175468565.dkr.ecr.eu-north-1.amazonaws.com/openbank-*`,
so they select every openbank service pod in every namespace — measured 2026-08-13: **74 of 416
running pods across 46 namespaces**. Only `kube-system` and `kyverno` are excluded. Since stage 2 of
#11437 it is `validationActions: [Deny]` with `failurePolicy: Fail`: a webhook that is *down or slow*
fails closed too (the v1 policy was `Ignore`). See §4a for its break-glass.

This runbook exists because issue #1915 asked for it before those policies graduated, and they
graduated first. ADR-0030 D4 is the decision; this is the way back out.

---

## 1. Confirm it is admission, and which policy

```
kubectl -n <ns> describe replicaset <rs>        # or `describe pod`, or the Rollout's events
```

An admission denial names the policy and the rule:

```
admission webhook "validate.kyverno.svc-fail" denied the request:
  policy Pod/<ns>/<name> for resource violation:
    Policy verify-openbank-image-sbom-attestation-cel failed: ... .../openbank-<svc>:<tag> ...
```

Read the **error text** out of that message — it is one policy and one rule now, so the policy
name no longer tells you which artifact is missing. `no signatures found` means the image signature,
`no matching attestations` means the SBOM attestation; they need different fixes. If nothing is
denied, this is not your problem: check the alerts instead
(`KyvernoAdmissionDenied`, `KyvernoEnforcePolicyBlocking` in
`openbank-infra/gitops/components/observability/prometheus-rules-kyverno.yaml`).

Fleet view of what is currently being refused:

```
kubectl get clusterpolicy                       # ACTION column: Enforce vs Audit
kubectl get polr -A -o wide | grep -i fail      # PolicyReports: who fails today
```

## 2. Know which trap you are in before you act

Three failure shapes have actually happened here. They look identical at the pod and are not.

- **The image was never signed/attested.** The fix is a rebuild — `build-push-{service,admin-ui}.sh`
  sign on push. Do **not** hand-roll `trivy` + `cosign attest`: source
  `openbank-infra/scripts/lib/cosign-attest.sh`. `cosign attest` is **additive**, so a green
  `verify-attestation` can be about an *earlier* build's envelope, and Kyverno is **ALL-match**
  where cosign is any-match — the false-precondition trap from the SBOM-enforce incident (#1197).
- **`kubectl rollout undo` is rejected.** Expected, and it is the move everyone reaches for first.
  Only *current* workload templates were back-signed; a pre-signing revision references an unsigned
  historical tag, so the ordinary rollback is the one thing that cannot be admitted. Roll *forward*
  to a rebuilt+signed image instead.
- **The thing that would fix it needs a pod the policy blocks.** This is the deadlock that cost four
  days: on 2026-07-12 the SBOM policy graduated while `openbank-ci-runner`'s own attestation step was
  `continue-on-error` and silently failing, so every ARC runner pod was refused — and the only
  workflow that could rebuild an attested runner image needed a runner. See the header of
  `openbank-infra/gitops/components/kyverno/arc-runner-image-exception.yaml`, which records the
  incident and its root-cause fixes (#963, #1051). **If the blocked workload is part of the build or
  deploy path, go straight to §3b — you cannot rebuild your way out.** (The ARC runner exception
  that recorded that incident was deleted on 2026-09-13 once the runner image verified on its own;
  its history is in git: `git log --follow -- openbank-infra/gitops/components/kyverno/arc-runner-image-exception.yaml`.)

## 3. Get unblocked

### 3a. Preferred — a scoped `PolicyException` (narrow, reversible, leaves Enforce on)

Model it on `pricing-image-exception-cel.yaml` (a `policies.kyverno.io` `PolicyException`): namespaced, matched to `kind: Pod` in that one
namespace, listing the policy/rule names. Note the cost of the fold: an exception can no longer
waive the SBOM attestation while keeping the signature check — the rule is one rule, so excepting
it waives both for that namespace. This is strictly better than dropping the
policy, because every other namespace stays protected.

Write it as a gitops file with a header stating **why, when, and the removal condition**, then
Argo-sync. A temporary exception with no removal condition becomes permanent — the file above says
so from experience.

### 3b. Fleet-wide — drop the offending policy to `Audit`

Only when the blast radius is fleet-wide or the deadlock in §2 applies.

Edit the policy's `spec.validationActions` from `[Deny]` to `[Audit]` in its gitops file and
sync (for a v1 ClusterPolicy: `spec.validationFailureAction` `Enforce` -> `Audit`). **This is coarser than it used to be:** the signature and SBOM checks were deliberately
separate ClusterPolicies (the #770 lesson) so that dropping one did not weaken the other, and on
Kyverno v1.12.5 that separation made the verdict nondeterministic (#9805). Until the upgrade,
dropping this policy to Audit drops **both** checks. Prefer §3a whenever the blast radius allows it.

`Audit` blocks nothing, but it is not "off": violations keep landing in PolicyReports, which is
exactly the worklist you need for §4.

> Do not `kubectl edit`/`patch` the live ClusterPolicy as the durable fix. Argo owns these objects
> and will revert it, giving you a rollback that works for minutes and then stops.

## 4. Before flipping back to `Enforce`

1. `kubectl get polr -A` shows **zero** failing resources for that policy — not "the one I fixed".
   An Audit-mode policy already failing resources is a loaded gun; `KyvernoAuditPolicyFailingBeforeEnforce`
   fires on exactly this and is a blocker on the graduation, not a warning to ride out.
2. Verify the artifact independently of Kyverno's cache — the image-verify cache has no negative-result
   TTL, which is why both policies set `useCache: false`:
   ```
   cosign verify-attestation --key <policy public key> --type cyclonedx <digest>
   ```
   Verify by **digest**, not tag; `cosign attest` being additive means a tag can be green about an
   older envelope.
3. Remove any §3a exception you added in the same change, or it silently outlives the incident.

**Read the gate's EXIT CODE, not just its colour.**
`.github/scripts/check-fleet-attestations.sh` exits `0` (every declared image attested), `1` (a real
gap: `UNATTESTED` and/or `ABSENT`) or `2` (`UNKNOWN` — the probe could not run for at least one
image: ECR throttle, 5xx, expired credentials, a cosign crash). **A 2 is not a verdict about any
image**: re-run it, and if it persists treat it as a registry/credential problem, never as an image
to rebuild. `Verify fleet attestations` prints a `could not run` warning annotation in that case, and
the scheduled run deliberately does NOT open a fleet-gap issue for it.

That distinction was paid for once. Until #1915 the loop special-cased only
`NAME_UNKNOWN|MANIFEST_UNKNOWN|404` as absent and let **every other** non-zero cosign exit fall
through to `UNATTESTED`, so a transient failure was published as a supply-chain verdict: run
`31729895636` (2026-08-13) reported
`UNATTESTED openbank-release-steward:sandbox-e80f4bc7 … 61 attested / 1 unattested / 62 total`,
while the **24 other runs of that same gate that day passed on the identical, unchanged image**, and
`cosign verify-attestation --key awskms:///alias/openbank-cosign-signing --type cyclonedx` against
its digest `sha256:31d626…` returned *"The signatures were verified against the specified public
key"*. Classification is now positive in both directions (an image is called `UNATTESTED` only when
cosign says so in words) and each candidate failure is retried `VERIFY_ATTEMPTS` times first — but a
red `1` still deserves the hand check: verify the named image by digest before rebuilding anything.

### What to copy from this into the next probe

Five rules, each of which this gate broke:

1. **A checker that knows the difference must ENCODE the difference in the one thing its caller
   reads.** Knowing "this was a throttle, not a gap" is worth nothing if both outcomes exit `1`:
   the workflow's `if: failure()` fires either way and files the same supply-chain ticket. Give
   "could not run" its own exit code (`2`) and branch on it in the caller —
   `.github/scripts/check-verification-metadata-complete.py` had already paid for this once
   (#4162, three shards dead of `Java heap space` reported as dependency drift). The reasoning
   generalizes past exit codes: it is the same defect as a skipped/disabled adapter sharing a
   `success` boolean with a real success — a distinct state needs a distinct value, not prose in
   a log nobody parses.
2. **Classify positively in both directions; never let the alarming verdict be the fallback
   branch.** Every failure mode nobody enumerated lands in the fallback, and the set of ways a
   registry call can fail is open-ended while the set of ways cosign says "not attested" is
   closed. Match the closed sets — absence, and an explicit attestation verdict — and route the
   remainder to "unknown". Written the other way round, the gate is guaranteed to manufacture a
   false supply-chain finding eventually; that is not bad luck, it is the structure.
3. **Retry only the class that is not a verdict.** Retrying a real `UNATTESTED` would slow a true
   gap down and, worse, invites the next author to widen the retry until a verdict is retried
   into existence. A verdict is final on the first attempt that produces one.
4. **A "could not run" path is unfalsifiable by CI here — prove it another way or it is code
   nobody has run.** No PR can summon an ECR throttle on demand, so the green run of this gate on
   the fixing PR says nothing about the branch that matters. Prove it with a stubbed `COSIGN_BIN`
   (a script that fails per-image the four ways) plus `check-fleet-attestations.sh --selftest`,
   which needs no registry and runs in the lint job on every PR. Falsify the selftest itself by
   reverting the classifier to the old fallback and confirming exactly the transient cases go
   red — a classifier that has only ever agreed with you is unfalsified. Because
   `fleet-attestation.yml` is path-filtered and can therefore never be a required check, that
   selftest is *also* declared as gate `fleet-attestation-classifier` in
   `.github/gates/gates.yaml`, so it runs unconditionally in `Validate manifests`.
5. **A stub proves your parser, not the vocabulary — pair it with a live known-negative
   control.** The fix in rule 2 buys correctness at the price of a new dependency: `UNATTESTED`
   is now matched on cosign's own wording, so a future cosign that rewords it re-routes every
   real gap to `UNKNOWN`. The gate would then exit `2` forever, never file a gap, and read as
   an infrastructure problem — a strictly worse failure than the one being fixed, because it
   fails in the reassuring direction. Nothing offline can catch that. The
   `Vocabulary control` step does: it asks the real binary, in the real registry, for a verdict
   it knows must be negative (a real fleet image with `--type spdx`, which nothing here
   attests) and fails loudly if that stops classifying as `UNATTESTED`. Generalize: whenever a
   verdict depends on parsing a third party's prose, keep one live case whose answer you
   already know, or the parser is only ever tested against your own memory of the wording.

## 4a. CEL shadow policies (Kyverno 1.19 migration, stages 1-2)

Every `kyverno.io/v1` ClusterPolicy in `gitops/components/kyverno/` not yet listed under stage 2 below has a
`policies.kyverno.io/v1` twin named `<policy>-cel` (`ValidatingPolicy`, `ImageValidatingPolicy`,
). They are `validationActions: [Audit]` with `failurePolicy: Ignore`,
so **they are never the thing denying a workload** — the rollback above still targets the v1 policy.
A `-cel` name in `KyvernoAuditPolicyFailingBeforeEnforce` is a parity gap, not an outage: compare
with the v1 policy's verdict and fix the CEL port before stage 2. If a shadow ever does misbehave
(admission latency, registry load), delete its file by PR; nothing depends on it yet.

**Stage 2 progress (#11437), one policy per PR:** `deny-nginx-snippet-annotations` — enforcing as
`deny-nginx-snippet-annotations-cel` (`[Deny]`, `failurePolicy: Fail`), v1 file deleted.
`require-gated-or-declared-tool-ingress` — enforcing as `require-gated-or-declared-tool-ingress-cel`
(`[Deny]`, `failurePolicy: Fail`), v1 file `tool-ingress-gate-policy.yaml` deleted. The v1 verdicts
of both are pinned in the parity harness. `openbank-dr-sa-pin` — enforcing as `openbank-dr-sa-pin-cel`
(`[Deny]`, `failurePolicy: Fail`); its v1 ClusterPolicy lived in `components/platform/dr-runner-rbac.yaml`
(Argo app `platform`, not `kyverno-policies`), so it was removed by a separate follow-up PR after the
flip had synced — two apps sync independently and `PruneLast` cannot order across them. The RBAC
documents in that file stay; the ClusterPolicy document is gone. Its v1 verdicts are pinned in the harness too (reverting the
removal PR re-creates it). For those policies the rollback targets the `-cel`
document, and reverting the stage-2 PR re-creates the v1 original.
`block-deployment-if-rollout-exists` — enforcing as `block-deployment-if-rollout-exists-cel`
(`[Deny]`, `failurePolicy: Fail`), v1 file `rollout-bypass-prevention.yaml` deleted; its v1 verdicts
were measured live and are pinned in `V1_ROLLOUT`. It is the one stage-2 policy that calls the API
server (`resource.List` over `argoproj.io` Rollouts), so under `Fail` a List error — Rollout CRD gone,
or the `kyverno-admission-controller` SA losing `list rollouts` — denies every Deployment CREATE/UPDATE
in its 13 money-path namespaces (v1 had the same exposure). Symptom: Deployment applies there fail
with a Kyverno webhook error naming that policy and a List/forbidden/not-found cause. Fix the CRD or
RBAC; if that cannot be quick, set that document's `failurePolicy: Ignore` by PR (it then admits on
error, keeping Deny when the List succeeds).
`ecr-pull-through-rewrite` (the one mutating policy) — rewriting as the `MutatingPolicy`
`ecr-pull-through-rewrite-cel` (kill-switch matchCondition removed, `failurePolicy: Ignore`, background
off, so existing Pods are never touched), v1 file deleted; its v1 output images are pinned in
`V1_IMAGES`. A mutation has no Audit mode, so the swap relied on idempotency instead: a rewritten ref
starts with the ECR host, which matches no origin prefix, so either engine on the other's output is a
no-op (the harness re-runs the CEL policy on its own output and requires zero changes). Symptom if it
misbehaves: new Pods show an unexpected image, or pulls fail with an ECR `not found` for a
pull-through path. Because it is `Ignore`, a webhook outage only means images pull from the origin
registry over NAT; to stop the rewrite, delete the file by PR (Pods then pull from the origin). CNPG
instance pods are excluded, as before.
`verify-openbank-image-sbom-attestation` (the image policy) — enforcing as the `ImageValidatingPolicy`
`verify-openbank-image-sbom-attestation-cel` (`[Deny]`, `failurePolicy: Fail`, `mutateDigest: false`),
v1 file `verify-sbom-attestation-policy.yaml` and its v2 exception `pricing-image-exception.yaml`
deleted; `pricing-image-exception-cel.yaml` carries the pricing waiver. **Break-glass, and what each
lever actually does** (Kyverno 1.19.1, `pkg/webhooks/resource/ivpol/handler.go`, `validationResponse`):
for a policy whose actions include `Deny`, a result of `RuleStatusFail` **and** `RuleStatusError`
both become an admission error — `failurePolicy` is not consulted there. So:
- *Kyverno unreachable or timing out* (the webhook CALL fails): set `failurePolicy: Ignore` by PR.
  That is the only case `failurePolicy` governs; the apiserver then admits without asking.
- *ECR, KMS or the key alias failing* (Kyverno answers, but verification errors): `failurePolicy:
  Ignore` does **nothing** — the Pod is denied with `Policy verify-openbank-image-sbom-attestation-cel
  error: ...`. The real break-glass is `validationActions: [Audit]` by PR (§3b): without `Deny` the
  per-policy Fail/Error branches are skipped and the verdict only lands in PolicyReports. One residue
  even then: an error from the engine as a whole (`HandleValidating` returning `err`, before any
  per-policy result) is still returned as an admission error by `validate`; that is not a
  verification verdict and has not been observed here.
- *One namespace only*: a `PolicyException` modelled on `pricing-image-exception-cel.yaml` (§3a).
All other policies are still at stage 1.
Parity harness: `bash openbank-infra/tests/kyverno-cel/run.sh`.

## 5. Related

- ADR-0030 D4 (supply-chain verification), ADR-0144 (graduation criteria)
- #770 — first signature-Enforce attempt, reverted (kyverno 3.2.6 cannot discover cosign v3 OCI-1.1
  referrer signatures; fixed by pinning cosign v2 tag-based signatures)
- #1197 — the `.att` manifest rewrite / ALL-match vs any-match trap
- #1915 — the issue this runbook closes out
- #9805 item 4 — why the verify policies are folded into one on Kyverno v1.12.5, and why an Audit
  verify policy could deny
