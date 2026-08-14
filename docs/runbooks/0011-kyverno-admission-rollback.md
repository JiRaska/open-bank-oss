# Runbook 0011 — Kyverno admission rollback (supply-chain verification policies)

**Scope:** the two `Enforce` supply-chain ClusterPolicies reject a *legitimate* workload at
admission and you need it running now.

| policy | what it requires | file |
| --- | --- | --- |
| `verify-openbank-image-signatures` | a valid Cosign signature (KMS public key + Rekor tlog) | `openbank-infra/gitops/components/kyverno/verify-images-policy.yaml` |
| `verify-openbank-image-sbom-attestation` | a `cyclonedx` attestation on the same image | `openbank-infra/gitops/components/kyverno/verify-sbom-attestation-policy.yaml` |

Both match **`kind: Pod`** with `imageReferences: 265175468565.dkr.ecr.eu-north-1.amazonaws.com/openbank-*`,
so they select every openbank service pod in every namespace — measured 2026-08-13: **74 of 416
running pods across 46 namespaces**. There is no namespace exclusion. `failurePolicy: Ignore`, so a
webhook that is *down* fails open; a webhook that is *up and says no* fails closed.

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
    verify-openbank-image-signatures:
      verify-cosign-signature: 'failed to verify image ...: .../openbank-<svc>:<tag>: no signatures found'
```

Read the **policy name** out of that message — the two policies fail with different text
(`no signatures found` vs `no matching attestations`) and need different fixes. If nothing is
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
  deploy path, go straight to §3b — you cannot rebuild your way out.**

## 3. Get unblocked

### 3a. Preferred — a scoped `PolicyException` (narrow, reversible, leaves Enforce on)

Model it on `arc-runner-image-exception.yaml`: namespaced, matched to `kind: Pod` in that one
namespace, listing only the offending policy/rule names. This is strictly better than dropping the
policy, because every other namespace stays protected.

Write it as a gitops file with a header stating **why, when, and the removal condition**, then
Argo-sync. A temporary exception with no removal condition becomes permanent — the file above says
so from experience.

### 3b. Fleet-wide — drop the offending policy to `Audit`

Only when the blast radius is fleet-wide or the deadlock in §2 applies.

Edit **one** policy's `spec.validationFailureAction` from `Enforce` to `Audit` in its gitops file
and sync. They are deliberately **separate** ClusterPolicies (the #770 lesson) — dropping the SBOM
rule does not weaken signature verification, so drop only the one that is denying.

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

**Do not treat a single red `Verify fleet attestations` run as the answer.**
`.github/scripts/check-fleet-attestations.sh` special-cases only `NAME_UNKNOWN|MANIFEST_UNKNOWN|404`
as "absent"; **every other** non-zero `cosign verify-attestation` exit — including a transient ECR
throttle or 5xx — is counted and printed as `UNATTESTED`, i.e. a probe failure is reported as a
supply-chain verdict. Measured 2026-08-13: run `31729895636` reported
`UNATTESTED openbank-release-steward:sandbox-e80f4bc7 … 61 attested / 1 unattested / 62 total`,
while the **24 other runs of that same gate that day passed on the identical, unchanged image**, and
`cosign verify-attestation --key awskms:///alias/openbank-cosign-signing --type cyclonedx` against
its digest `sha256:31d626…` returns *"The signatures were verified against the specified public
key"*. Before acting on a red gate, re-run it and verify the named image by digest by hand.

## 5. Related

- ADR-0030 D4 (supply-chain verification), ADR-0144 (graduation criteria)
- #770 — first signature-Enforce attempt, reverted (kyverno 3.2.6 cannot discover cosign v3 OCI-1.1
  referrer signatures; fixed by pinning cosign v2 tag-based signatures)
- #1197 — the `.att` manifest rewrite / ALL-match vs any-match trap
- #1915 — the issue this runbook closes out
