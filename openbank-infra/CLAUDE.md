# openbank-infra — agent & contributor guide

Cluster substrate, GitOps manifests, OPA policy bundles, and the supply-chain scripts
(`scripts/lib/cosign-attest.sh`) the CI producers call. See the root `CLAUDE.md` for
monorepo-wide rules; the pitfalls below fire when you touch **this** tree and were split
out of it (they are path-scoped, not less important — several are live-incident lessons).

## Engineering notes (common pitfalls)

### GitOps / Kubernetes
- **`realm-template.json` is read ONLY on Keycloak's cold start (`--import-realm`), so editing it
  changes nothing on a running realm — and ArgoCD reports `Synced/Healthy` throughout.** ArgoCD
  manages the ConfigMap, and the ConfigMap does match the repo; the drift is one layer below what it
  can see. Measured 2026-07-26 (#2540): the live sandbox realm was missing `ROLE_KYC`,
  `ROLE_KYC_OPENER`, `ROLE_KYC_REVIEWER` and `ROLE_SUPERVISOR` that the template declared, and
  carried `ROLE_DEMO` that it did not. Nothing 403'd — every `@RolesAllowed` also listed a role that
  did exist — but the ADR-0116 KYC four-eyes split cannot be enforced by roles that are absent, so
  opener and reviewer both fell through to ROLE_ADMIN/ROLE_OPERATOR and one identity could do both.
  `check-roles-allowed-realm.py` was green the whole time: it compares the code to the TEMPLATE.
  Verify against the realm that actually runs (`kcadm.sh get roles -r openbank`) before believing any
  claim about which roles exist, and apply additions with `kcadm` — the file alone will not.
- **A change under `openbank-libs-*/src/main/**` rebuilds the WHOLE fleet, and the deploy that
  follows fails on pacts that do not exist yet.** `Detect changed services` returned 58 modules for
  the #2475 role sweep; at `max-parallel: 4` and ~45 min a service that is ~11 h of queue. Auto-deploy
  fires on `push`, runs immediately, and `can-i-deploy` answers `NOT deployable` — not because a
  contract regressed but because the build has not published anything yet ("no pacts or verifications
  have been published for version X"). Both readings print identically. Auto-deploy has no
  `workflow_run` trigger and no schedule, so it never retries: the services stay on the old image
  until some later commit happens to touch them (issue #2549). After any libs-level change, expect to
  re-dispatch `auto-deploy.yml` per service once Services CI finishes, and check
  `GET /pacticipants/openbank-<svc>/versions/<sha>` on the broker before concluding anything from a
  `can-i-deploy` verdict.
- **A no-swap node under memory pressure can hang whole-guest instead of OOM-killing.** Kernel
  reclaim livelocks; kubelet and the SSM agent starve together while EC2 status checks stay `ok`,
  so the node lingers NotReady and singleton pods (e.g. the ArgoCD application-controller) strand
  (issue #809). Diagnosis shortcut: CloudWatch `CPUUtilization` pinned at a constant high plateau
  for hours = livelock — terminate the instance, don't debug the guest. The defenses are layered
  and ALL needed: honest memory *requests* (Karpenter bin-packs by requests — an undeclared
  ~200Mi-per-node DaemonSet is what actually kills 4Gi nodes), kubelet eviction headroom in the
  EC2NodeClass (the AMI default `memory.available<100Mi` reacts too late), memory *limits* on
  singletons (a container OOM-kill self-heals; a dead node doesn't), and Karpenter `NodeRepair`
  as the backstop (EKS node auto repair covers only managed node groups, and consolidation cannot
  touch a node holding a `do-not-disrupt` pod).
- **Right-sizing requests can pin a NodePool at its `limits` cap.** The cap was calibrated to the
  old, understated requests; after raising them Karpenter may refuse to provision
  ("all available instance types exceed limits for nodepool"), leaving pods Pending and stalling
  drift rolls. Whenever you raise requests or eviction headroom, re-check the pool's `limits`.
- **`optional: true` on a secret ref is a deliberate trade, not a default — know which way you want
  it.** Without it, a missing Secret pins the pod at `CreateContainerConfigError`: loud, impossible to
  miss, fixed within the hour (vop-oidc, #1232). With it, Kubernetes silently drops the env/volume and
  the pod runs `2/2` reporting `UP` while the feature it needed is quietly degraded — document-service
  sealed PDFs with a throwaway cert for two days that way (#1284), and the ONLY signal was an ArgoCD
  `Degraded` nobody acted on. `optional: true` is right when a rollout-order race must never
  crash-loop the service (gitops wiring merges before the OpenBao KV secret is seeded) — but then the
  fallback needs its own loud, *eager* alarm, or the silence is the bug.
- **An Argo Rollout that never once succeeded deadlocks on a dead `stable`.** If revision 1 never
  became healthy (image absent, Kyverno denial), `stableRS` stays pinned to it forever; the canary
  can't scale because canary strategy holds replicas on a stable that can never schedule. The Rollout
  loops `Progressing` → `Degraded` (`progressDeadlineSeconds`) while a *later* revision serves happily
  — service up, Rollout permanently red. **Neither `kubectl argo rollouts retry` nor `promote --full`
  fixes it** — promote skips steps and pauses, not a broken stable. Delete the dead ReplicaSet
  (`kubectl -n <ns> delete rs <name>-<rev1-hash>`); Argo then adopts the current revision and goes
  Healthy in ~90s. Verify it owns **zero** pods first (`kubectl get pods -l
  rollouts-pod-template-hash=<hash>`). Seen on vop-service, 2026-07-16.
- **`strategy.type: Recreate` + Server-Side Apply = HTTP 403.** Use `RollingUpdate` with
  `maxSurge: 0 / maxUnavailable: 1` for identical zero-concurrency behaviour.
- **Use explicit registry prefixes for container images** (`docker.io/library/<image>` for official
  images) so the cluster's pull-through/rewrite policies apply.
- **`trivy image` defaults to `linux/amd64` for remote scans, regardless of host arch.** Once a build
  moves to a native `linux/arm64` builder (sandbox nodes, arm64 hosted runners), a plain
  `trivy image ... "${IMAGE}"` against the pushed (arm64-only) image fails with `no child with
  platform linux/amd64 in index` — silently, if the caller only checks the exit code. Pass
  `--platform` explicitly, matching the arch the image was actually built for. A skipped SBOM
  attestation here means Kyverno's `verify-openbank-image-sbom-attestation` policy blocks every pod
  admission for that image (admin-ui outage, 2026-07-09). Producers must call the shared
  `openbank-infra/scripts/lib/cosign-attest.sh` (which passes `--platform` and hard-fails), never
  hand-roll `trivy`+`cosign attest` again.
- **Kyverno verifies at ADMISSION, not continuously — a running pod is NOT evidence its image is
  attested.** An unattested image keeps running; it is denied only on the next reschedule (node
  roll, eviction, scale-up) and then can never restart, one pod at a time. So "the fleet is
  healthy" / "PolicyReport shows 0 fail" only describes pods that happen to exist right now, and
  never justifies an Audit→Enforce flip. Run `.github/scripts/check-fleet-attestations.sh` (daily
  via `fleet-attestation.yml`) — it checks every image *declared* in gitops, incl. initContainers
  and sidecars, so a gap is caught while still latent. Green gate before any Enforce graduation
  (`rules.yaml: provenance.fleet_attestation_gate`).
- **A provenance failure must fail the build that caused it.** `continue-on-error` /
  `|| echo "::warning::"` on a sign/attest step buys a green deploy that produces an
  undeployable image — the damage lands days later on whoever is on call, not on the author.
  Verify with `cosign verify-attestation` after attesting; never trust `attest` exit 0 alone —
  but see the next bullet: an unqualified verify does not prove YOUR envelope is the good one.
- **`cosign attest` is ADDITIVE, so a green `verify-attestation` is not necessarily about your
  build.** Each attest APPENDS an envelope to the image's `.att` tag instead of replacing, and
  `verify-attestation` exits 0 if **any** envelope verifies. cosign v2 has no strict/newest-envelope
  flag (`--policy` is any-match too — it errors only when *zero* attestations match). So a trivy run
  that emits a truncated SBOM and still exits 0 pushes a junk predicate, and the verify passes
  against an *earlier* build's envelope: green build, image shipped with provenance that is
  technically present and substantively worthless. Proven live — an image carrying both a
  549-component SBOM and a 2-byte `{}` predicate verifies PASS. Two layers, both needed: check the
  SBOM BEFORE attesting (parses as JSON, `bomFormat == "CycloneDX"`, non-zero `components` — a junk
  envelope, once pushed, can never be un-pushed, only outlived by the next real build), and bind the
  post-attest verify to the run by requiring the CycloneDX `serialNumber` trivy minted for THIS SBOM
  to appear among the envelopes that verified. `openbank-infra/scripts/lib/cosign-attest.sh` does
  both — source it, never hand-roll `attest` + `verify`.
- **`gen-network-policies.py` derives ingress allow-lists ONLY from cross-namespace URLs in a
  Deployment/Rollout `env` block — a URL that lives only in the service's `application.yaml`
  produces no edge at all.** The caller's namespace is then silently absent from the callee's
  allow-list and the traffic is DROPPED by the VPC CNI agent in standard mode (ADR-0060) — no
  manifest error, no policy diff, just a hung call (party-service's GDPR Art. 15 hops to
  kyc-service and card-issuance-service, #1784). Declare every cross-namespace URL in the gitops
  manifest env, not only in `application.yaml`.
- **"`components/<svc>/` has no `network-policies.yaml`" is NOT evidence the service is unprotected
  — check the namespace, not the directory.** Until #2207 `gen-network-policies.py` emitted one file
  per *namespace*, into the first component directory alphabetically that declared a workload there.
  `platform` is shared by `agent`, `ap2`, `copilot` and `mcp`, so all four services' allow-lists sat
  in `components/agent/network-policies.yaml`; `documents` (renderer + service) and `messaging`
  (apicurio + kafka) had the same shape. The MCP threat model (PR #2200) recorded "NetworkPolicy:
  NONE" on that basis while `mcp-service-ingress-allow-list` had been live since the phase-1 deploy,
  and the ADR-0081 drift gate was correctly green throughout — there was no drift to find. Output is
  now keyed by component directory (so each ArgoCD Application owns the policies for its own
  workloads), but the reflex stands: verify with
  `kubectl get netpol -n <ns>` / `grep -rl '<workload>-ingress-allow-list' gitops/components/`,
  never with `ls components/<svc>/`.
- **The generator's `URL_RE` requires a literal `.svc` — `http://kyc-service.kyc:8114` does NOT
  match, `http://kyc-service.kyc.svc:8114` does.** Write the short form and the generator exits 0
  and changes nothing: a silent no-op indistinguishable from "already in sync". Always diff the
  regenerated `network-policies.yaml` files; never trust the generator's exit code.

### OPA / Rego policies (ADR-0031/ADR-0034)
- **Editing any shared policy source ripples the OPA bundle checksum of every service.** Each
  `openbank-infra/gitops/components/**/gen-*opa-bundle*.sh` embeds `rest.rego`, `agents.rego`,
  `agents.yaml` and (25 of the 26) `rules.yaml` verbatim into its ConfigMap and hashes them into
  that service's `openbank.tech/policy-checksum` annotation. So a new charter entry for a
  completely unrelated agent — or any `rules.yaml` edit — still changes *every* service's bundle
  and annotation. `opa-policy.yml`'s "build + verify bundle" job discovers the generators with
  `find` and regenerates **all** of them on every OPA-relevant PR, so your PR fails there unless it
  re-runs and commits every generator's output, not just your own service's bundle. Regenerate with:
  ```
  find openbank-infra/gitops/components -name 'gen-*opa-bundle*.sh' | sort | xargs -n1 bash
  ```
  Expect this to roll the pods of every service whose checksum moved — that is the point (subPath
  mounts do not hot-reload). Do not hand-edit a bundle or an annotation to dodge the diff.
- **The generator list is discovered, not hard-coded — keep it that way.** Until #1184 the gate
  named four generators while 25 hashed `rules.yaml`, so ~21 services' committed bundles drifted
  from the policy source for months with CI green: the deployed OPA data (`data.rules.*`,
  `data.agents.*`) silently lagged what `rules.yaml` declared. If you add a generator, add nothing
  to CI — but do commit it mode `100755`: a non-executable generator no-ops a `./…` loop and looks
  exactly like "in sync" (`gen-ledger-opa-bundle.sh` shipped `100644` and had never once run).
- **An `AI_AGENT` principal's id carries an `agent:` prefix on the REST path, but not on the
  MCP path.** `AuthorizeInterceptor.principalType()` classifies `AI_AGENT` from a JWT `sub`
  prefixed `agent:`, and `principal.id` is that sub verbatim — but `openbank-agent-service`
  sets `agent` to a bare charter id (`"ui-assistant"`) directly from its own config on the MCP
  `/tools/call` path. A charter lookup that compares `principal.id`/`input.agent` straight
  against `agents.yaml` ids must strip the prefix first (`trim_prefix(input.agent, "agent:")`,
  a no-op when absent) or it silently never matches for every real REST call.
- **`rest.rego` must delegate AI-agent REST calls to `agents.allow`, never `agents.charter_allowed`
  directly.** Only `allow` also applies `hard_denied` / `charter_denied` / `skill_ok` — calling
  `charter_allowed` alone lets a fleet-wide hard-denied tool tier or a charter's own `tools.deny`
  glob silently reach a REST action anyway.
