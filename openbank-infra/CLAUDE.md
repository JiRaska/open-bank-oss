# openbank-infra — agent & contributor guide

Cluster substrate, GitOps manifests, OPA policy bundles, and the supply-chain scripts
(`scripts/lib/cosign-attest.sh`) the CI producers call. See the root `CLAUDE.md` for
monorepo-wide rules; the pitfalls below fire when you touch **this** tree and were split
out of it (they are path-scoped, not less important — several are live-incident lessons).

## Engineering notes (common pitfalls)

### GitOps / Kubernetes
- **ArgoCD reports `Synced / Healthy` when it cannot compute a diff at all, and will then apply
  NOTHING for as long as that lasts — measured at three weeks.** With `ServerSideApply=true` the
  controller runs a server-side dry-run to diff; if that dry-run is rejected the comparison ERRORS,
  and the app keeps serving its last known status. The condition lands in
  `.status.conditions[].type == ComparisonError`, which **no ArgoCD metric exposes** — `argocd_app_info`
  carries only `sync_status` and `health_status`, so `ArgoCDAppDegraded` and `ArgoCDAppHealthUnknown`
  in `prometheus-rules-argocd.yaml` both stay quiet by construction. The trigger is any change to an
  IMMUTABLE field: on 2026-08-21 the `loki` app had `singleBinary.persistence.size` raised 10Gi ->
  30Gi (#3278), StatefulSet `volumeClaimTemplates` is immutable, and every values change merged
  after that — including #6032's ruler fix — was never applied. `kubectl get application` showed
  `Synced Healthy` throughout.
  **Read `.status.operationState.finishedAt`, not `.status.sync.status`.** A last successful sync
  three weeks old on an app whose values have changed since is the tell; `Synced` is not evidence.
  The confirming probe is to render the chart locally with the app's own live values
  (`kubectl get application <n> -o jsonpath='{.spec.source.helm.valuesObject}'` -> `helm template`)
  and diff against the live ConfigMap — that is what turned "Argo says Synced" into "the ruler block
  is the only thing missing". Fix without downtime: `kubectl delete sts <n> --cascade=orphan` leaves
  the pod and the PVC running and lets Argo recreate the object with the new template; the PVC keeps
  whatever size it was expanded to separately.
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
- **"Template agrees with the live realm" is NOT "the realm is reproducible" — there is a THIRD
  artifact, and it is the one a rebuild reads.** `keycloak.yaml`'s `realm-import` volume projects the
  Secrets `keycloak-realm-import` / `keycloak-customers-realm-import`, which ExternalSecrets fills
  from Vault KV; the committed template feeds nothing. So the two comparisons that existed
  (`check-roles-allowed-realm.py` code→template, `check-realm-role-parity.py` template→live) can
  BOTH be green about a realm that a green-field rebuild would not reproduce, and were: measured
  2026-08-03 (#3246) the import artifact held 4 roles / 2 clients / 1 user against the template's
  14 / 10 / 6, and 1 client against the customers template's 3 — while template and live agreed
  exactly in both realms. It is a strict ANCESTOR, never a divergent fork, which is why the agreed
  direction is Vault-converges-to-repo (nothing live is dropped) rather than scoping the enforced
  gate down to the 4-role blob. `check-realm-import-parity.py` is the third comparison, run by the
  `keycloak-realm-drift` CronJob off the SAME projected Secrets Keycloak mounts — never a second
  copy, or it drifts from the one that deploys. It reads NAMES only: the template carries
  `__PLACEHOLDER__` where the artifact carries real client secrets, and the report is a ConfigMap.
  Today's gap is baselined in its `IMPORT_BASELINE` (#2540's ordering point: a detector shipped
  before the reconcile is an alert that is the resting state from minute one, clearable only by a
  Vault write). **Baseline the FROZEN side, never the difference.** The first version baselined the
  gap — a function of both sides — so every legitimate template addition read as new drift: #4028
  declared one service-account user and the CronJob went red nightly for six days on a run where
  the drift had not changed, with "append the name to the baseline" as its only remedy. The
  baseline now records what the import artifact CARRIES, so template growth is silent and the
  artifact MOVING (a Vault write, in either direction) is the finding. Generalises past Keycloak:
  any baseline keyed to `A - B` where only `B` is frozen will fire on changes to `A`.
  The reconcile procedure is `docs/runbooks/0009-keycloak-realm-import-reconcile.md`; the
  `vault kv put` recipe in `components/external-secrets/README.md` reads the LIVE Secret back, so
  re-running it as maintenance can only re-store the stale ancestor.
- **ArgoCD does NOT diff hook resources — so anything a hook reads from its own manifest can never
  be changed in a way that triggers it.** A `argocd.argoproj.io/hook` object is excluded from the
  Application's desired-state comparison: `kubectl -n argocd get application <app> -o json` reports
  every other resource `Synced` and the hook `status: None`, i.e. not compared at all. Editing only
  the hook therefore produces no diff, no sync operation, and no hook run — the Application sits
  `Synced/Healthy` at the new revision having done nothing. `temporal-namespace-registration` carried
  its `NAMESPACES` list inline that way, and "add a namespace to the list" is the ONLY edit that file
  ever receives, so the mechanism was inert for its sole use case; it had worked only when a namespace
  addition happened to ride along with an unrelated change to a non-hook resource in the same
  Application (settlement and campaign both reached production polling namespaces that did not exist,
  and after #3475 `openbank-lending` needed a hand-issued sync). Its own comment asserted the
  behaviour it did not have — the file was the only thing claiming the trigger existed. Fix, and the
  general rule: **a hook's INPUT belongs on a resource ArgoCD compares** (a ConfigMap the hook reads
  via `configMapKeyRef`/volume), so changing the input is what makes the app OutOfSync and the
  resulting sync operation runs the hook. Enforced by
  `check-temporal-namespace-registration.py` (#3507), which also rejects a second copy of the list
  and a ConfigMap no workload consumes. Corollary for anything a hook provisions OUTSIDE Kubernetes
  (a Temporal namespace, a realm, a bucket): ArgoCD cannot see it at all, so deletion out of band
  produces no diff either — pair the hook with a reconciling CronJob that repairs the gap **and then
  exits non-zero**, so KubeJobFailed carries it. A repair that exits 0 is one nobody ever learns about.
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
- **An inline `#trivy:ignore:<ID>` comment does NOT suppress a Kubernetes misconfig finding — the
  only lever is `.trivyignore`, which is repo-wide.** `Trivy config (IaC) scan` in `security.yml`
  runs `trivy config … --ignorefile .trivyignore openbank-infra`, and on trivy 0.72 the inline form
  was measured against all three plausible spellings (`KSV-0108`, `AVD-KSV-0108`, `KSV0108`) above
  the resource: the finding still reports in every case. So there is no narrow, in-place way to
  accept one resource — a baseline entry silently exempts every *future* occurrence too. When you
  add one, say so in the comment and pair it with something that counts the occurrences, so the
  second one has to be justified rather than inheriting the exemption. `AVD-KSV-0108` (the ADR-0234
  `grafana-tools` ExternalName Service, an in-cluster target the CVE-2020-8554 check cannot
  distinguish from an external one) is the worked example: `tools-gate.test.ts` asserts it stays the
  only `type: ExternalName` in gitops. Verify a baseline edit the same way — re-run with the OLD
  ignorefile and confirm it still exits 1, or you cannot tell your new line from the scan going
  quiet for some unrelated reason.
- **Kyverno verifies at ADMISSION, not continuously — a running pod is NOT evidence its image is
  attested.** An unattested image keeps running; it is denied only on the next reschedule (node
  roll, eviction, scale-up) and then can never restart, one pod at a time. So "the fleet is
  healthy" / "PolicyReport shows 0 fail" only describes pods that happen to exist right now, and
  never justifies an Audit→Enforce flip. Run `.github/scripts/check-fleet-attestations.sh` (daily
  via `fleet-attestation.yml`) — it checks every image *declared* in gitops, incl. initContainers
  and sidecars, so a gap is caught while still latent. Green gate before any Enforce graduation
  (`rules.yaml: provenance.fleet_attestation_gate`).
- **`gen-network-policies.py` emits INGRESS only, so a new in-cluster edge also needs the CALLER's
  hand-written EGRESS rule — and the omission is silent in both directions.** Measured 2026-08-20:
  LiteLLM's Langfuse trace callback died on `ConnectTimeout` to `langfuse.ai-platform.svc:3000`
  while both pods were 1/1 Running, ArgoCD was Synced/Healthy, the generated
  `langfuse-ingress-allow-list` correctly admitted the same namespace, and LiteLLM still answered
  200 to every caller — the only symptom anywhere was `/api/public/traces` returning an empty list.
  Same namespace is NOT the same as allowed: a pod carrying an egress policy is deny-by-default for
  everything that policy does not name, and `networkpolicy-litellm-egress.yaml` named DNS, 443 to
  the internet, and its own Postgres. Isolate it in one command — run a throwaway pod with no
  egress policy in the same namespace and curl the target (HTTP 200 there + timeout from the
  policed pod pins it to egress, not to the Service, DNS or the ingress allow-list). Note the
  throwaway pod needs a `restricted` PodSecurity context or the namespace refuses it.
- **`psql -tA` still prints the command-status line (`DELETE <n>`) interleaved with `RETURNING`
  data rows — `wc -l` on that output overcounts by exactly one.** `-q` (quiet) is what suppresses
  it; `-tA` alone is not enough. Measured against a real Postgres: `psql -tA -c "DELETE ... RETURNING
  1"` on a single-row delete emits `1\nDELETE 1\n` (two lines), `psql -qtA` emits `1\n` (one).
  A retention sweep counting deletions this way silently reports one MORE row deleted than actually
  happened, every single run — verified by seeding an old/new row pair and checking the sweep's
  reported count against the real remaining row count after (`langfuse-retention-cronjob.yaml`).
- **`gen-network-policies-drift-gate`'s `git add -A --intent-to-add openbank-infra/gitops/components/`
  stages EVERY untracked file under that tree, not just generated NetworkPolicy output — a brand-new,
  unrelated manifest in the same PR reads as "drift" until it is `git add`ed for real.** Not a bug in
  your manifest: it self-resolves the moment the file is staged as part of the normal commit, which
  is exactly what a real PR does. Only surprising when running the gate by hand against an untracked
  new file before committing it.
- **A ConfigMap a pod parses ONCE at startup needs a pod-roll annotation, or the edit is a no-op
  against a green ArgoCD.** LiteLLM reads `--config` at boot and the ConfigMap is a plain volume
  mount, so a new model route reaches the pod's filesystem and the proxy keeps serving the list it
  booted with: the caller gets `model not found` while ArgoCD reports Synced and Healthy and the
  in-cluster ConfigMap genuinely contains the route. `litellm.yaml` has carried "BUMP THIS whenever
  litellm-config.yaml changes" in a comment since #1919; prose is not a control, and three routes
  were added in one week each depending on someone remembering it. Now enforced by
  `check-litellm-config-revision.py` (`rules.yaml: litellm_gateway`) — any DIFFERENT annotation
  value counts, so a revert is not blocked by a monotonicity rule nobody agreed to. Generalize past
  LiteLLM: any workload that reads its config once (no `--watch`, no SIGHUP handler, no reloader
  sidecar) has this shape, and the reloader sidecar pattern in the alloy/prometheus components is
  the alternative fix.
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
- **An allow-list read as a flat set of namespaces has LOST the port dimension, and the flattened
  answer is the reassuring one.** A NetworkPolicy is a list of rules, each pairing its own `from`
  with its own `ports`; the same namespace can appear in one rule and be irrelevant to another.
  `keycloak-ingress-allow-list` names `observability` — on **TCP:9000**, the metrics port, in a rule
  of its own, while the `:8080` rule lists ~38 other namespaces and not this one. So
  `jq '.spec.ingress[].from[]'` says "observability is allowed" and Grafana's OIDC token exchange to
  `keycloak.iam.svc:8080` times out after 20 s anyway (#3145). Dump `ports` WITH `from`, per rule:
  `kubectl get netpol X -o json | jq -r '.spec.ingress[] | "PORTS=\((.ports//[])|map("\(.protocol//"TCP"):\(.port)")|join(",")) FROM=\([.from[]?|.namespaceSelector.matchLabels["kubernetes.io/metadata.name"]]|join(","))"'`
  — and prefer an actual connection over any reading of the YAML: a throwaway pod in the caller's
  namespace `curl`ing the real host:port settles it in one command, where the manifest cannot.
- **A workload that reaches another namespace from a HELM SUBCHART's config is invisible to
  `gen-network-policies.py` — it needs a hand-written policy, and nothing will tell you.** The
  generator reads env URLs off Deployments under `components/`; Grafana's
  `auth.generic_oauth.token_url` lives in a `grafana.ini` values block inside
  `apps/kube-prometheus-stack.yaml`, so that edge was never derived and never existed. Same blind
  spot already forced `networkpolicy-grafana.yaml` and `networkpolicy-rum-gateway.yaml` to be
  hand-written; policies are additive, so a separate file beside the generated one is the pattern
  (never edit the generated file). The trap underneath: this had been broken for as long as the
  config had existed, because Grafana had no Ingress and its SSO was reachable only by port-forward,
  which nobody had ever completed end-to-end. **Config that no one has exercised is not "working",
  it is untested** — routing a tool for the first time is exactly when its long-standing config gets
  its first real test, so expect to find something.
- **Moving a workload to a sub-path silently breaks every scrape and probe that still asks for a
  root path.** Grafana's `serve_from_sub_path` answers `/metrics` with a 301 to the sub-path rendered
  as an ABSOLUTE URL on `root_url`, so the chart-default ServiceMonitor followed it out to the public
  edge and hit the identity-aware gate's login redirect (ADR-0234). Nothing went red: the
  ServiceMonitor was healthy, the target was `up`, and it was scraping a login page — the symptom is
  a metric that stops arriving, and nothing alerts on that. When a sub-path lands, re-point every
  ServiceMonitor/Probe/readiness URL at it in the same change, and verify by asking for the two paths
  and comparing (`/metrics` -> 301, `/tools/<x>/metrics` -> 200), never by checking that the target
  is still `up`.
- **The generator derives ingress ports from EVERY container's `containerPorts` — a sidecar port
  that only ever serves loopback must be named into `SIDECAR_LOCAL_ONLY_PORT_NAMES`, or it is
  published to the app's whole caller set.** The OPA PDP sidecar is the case that forced the rule:
  the app reaches it at `http://localhost:8181` (`OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL`,
  no `OPA_URL` env in most components), so it needs no cross-namespace ingress at all — yet 8181
  landed in the SAME derived rule as the app's HTTP port on 29 components, admitting every declared
  caller namespace (for kyc: customer-edge, party, admin-ui, security-scanner) to an `opa run
  --server` with no `--authentication` and no `--authorization`. That is a policy ORACLE
  (`POST /v1/data/openbank/rest/allow` with arbitrary input), full disclosure of the rego plus
  `data.agents` / `data.rules` (`GET /v1/policies`, `GET /v1/data`), arbitrary rego evaluation
  (`POST /v1/query`, a CPU-burn DoS on a money-path pod) and writes to any data root the bundle's
  `.manifest` does not own. Decision *integrity* held — a bundle root is write-protected, so
  `openbank/rest/allow` itself cannot be overwritten. Fix is in the generator only; never hand-edit
  a derived `network-policies.yaml`. Residual, accepted: the unconditional same-namespace rule still
  reaches 8181 from co-tenant pods — NetworkPolicy cannot express "loopback only".
- **The generator's `URL_RE` requires a literal `.svc` — `http://kyc-service.kyc:8114` does NOT
  match, `http://kyc-service.kyc.svc:8114` does.** Write the short form and the generator exits 0
  and changes nothing: a silent no-op indistinguishable from "already in sync". Always diff the
  regenerated `network-policies.yaml` files; never trust the generator's exit code.

### OpenTofu / AWS substrate
- **An `aws_instance` whose `ami` comes from a `most_recent = true` data source has a REPLACE
  scheduled by a third party, on a timetable nobody here sets — and on the NAT that is an egress
  outage waiting for an unrelated apply.** `modules/network`'s fck-nat instance (ADR-0058) took its
  AMI straight from `data.aws_ami.fck_nat`, so every time the publisher shipped a patched image the
  plan silently re-armed `~ ami = ... # forces replacement`, dragging `aws_route_table.private`
  along with it because the default route points at the instance's primary ENI. Nothing was wrong
  with the state; the ORDINARY command was the landmine, and it fired for whoever next planned this
  stack for an unrelated reason — a one-line IAM fix is how it was found (#3602). Note that
  `substrate-tofu.yml` DOES plan this root on every PR touching `envs/sandbox-substrate/**` or
  `modules/**`, and applies it on manual dispatch; the preview was there and simply is not read on a
  PR about something else, which is the more uncomfortable version of the story. Fix:
  `var.nat_ami_id` pins the AMI and the data source is the bootstrap-only fallback, so a NAT upgrade
  is a reviewable one-line diff applied in a window.
  `check-nat-ami-pinned.py` (gate `nat-ami-pinned`) enforces both halves — the module must keep the
  pin variable in the `ami` expression, and every `egress_mode = "fck_nat"` env must pass a concrete
  `ami-...`. Generalize before reaching for `-target`: read the plan's `replace_paths`, and treat any
  forcing attribute fed by a resolved-at-plan-time value as a landmine rather than a diff.
- **A plan that is CLEAN of replaces can still carry a `create` for a resource that already exists
  in AWS but is missing from state.** `aws_eip_association.fck_nat[0]` sat that way (a `-target`ed
  apply is the likely cause), and its danger is entirely a function of what else the plan does: with
  the instance being replaced it would have moved the EIP to a new instance; with the instance
  unchanged the plan resolves `instance_id` to the SAME instance and the re-association is inert.
  Read the resolved attribute values, not the action verb. The clean repair is `tofu import`, which
  is a state write and therefore a deliberate operator step.

### OPA / Rego policies (ADR-0031/ADR-0034)
- **Editing any shared policy source ripples the OPA bundle checksum of every service.** Each
  `openbank-infra/gitops/components/**/gen-*opa-bundle*.sh` embeds `rest.rego`, `agents.rego`,
  `agents-opa-data.yaml` and (41 of the 42) `rules-opa-data.yaml` verbatim into its ConfigMap and
  hashes them into that service's `openbank.tech/policy-checksum` annotation. So a new charter
  entry for a completely unrelated agent still changes *every* service's bundle and annotation —
  but only if it touches a field a policy reads (see the `agents-opa-data.yaml` note below).
  `opa-policy.yml`'s "build + verify bundle" job discovers the generators with
  `find` and regenerates **all** of them on every OPA-relevant PR, so your PR fails there unless it
  re-runs and commits every generator's output, not just your own service's bundle. Regenerate with:
  ```
  python3 .github/scripts/gen-rules-opa-data.py
  python3 .github/scripts/gen-agents-opa-data.py     # needs `opa` on PATH
  find openbank-infra/gitops/components -name 'gen-*opa-bundle*.sh' | sort | xargs -n1 bash
  ```
  **`agents-opa-data.yaml` is DERIVED from `agents.yaml` the same way (#3927)** — the paths a
  `.rego` reads under `data.agents.*`, with each charter projected to the fields an iteration
  variable actually dereferences (`id`, `skills`, `tools.allow`, `tools.deny`). 50390 B → 5578 B
  per bundle, ~1.9 MB across the estate. The projection is one level deeper than `rules.yaml`'s
  top-level-key subset because that is the granularity `agents.rego` reads at; subsetting only
  top-level keys would have stripped ~7% and missed the point. Since a field projection cannot be
  argued from verbatim extraction, `gen-agents-opa-data.py` proves it by evaluating the real
  policy over a charter-derived input matrix under both documents and requiring every MCP and REST
  decision to match — that is why it needs `opa`, and why its `--self-test` deliberately feeds it
  a projection missing `skills`/`tools.allow` and requires the proof to reject it.
  **`rules-opa-data.yaml` is DERIVED from `rules.yaml` and must be regenerated first** — it is the
  subset of top-level keys some `.rego` reads as `data.rules.<key>`, extracted verbatim (#3357).
  Embedding the whole 168 KB file meant an edit to any of the ~30 keys OPA never reads restamped
  ~78 gitops files for nothing, colliding with the auto-deploy bot's image bumps in the very same
  `*-service.yaml` files; it also pushed every bundle against the 262144-byte annotation ceiling
  that had already frozen five Applications' policy. An edit to a non-read key now changes nothing
  here at all. Never hand-edit `rules-opa-data.yaml`, and never add a key to it by hand — the key
  set comes from the `.rego` sources, so `data.rules.<newkey>` picks itself up on regeneration.
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

### Prometheus / Loki rules — configured-looking and inert

- **An alert built on a threshold cannot see a subject that emits NOTHING, and `up` does not
  save you — Prometheus writes `up` per TARGET, so a workload nobody scrapes has no `up` either.**
  `PostgresInstanceDown` documents at length that `cnpg_collector_up` cannot report its own
  exporter's death and that `up` survives instead; that is right and stops one level short. A CNPG
  `Cluster` without `spec.monitoring.enablePodMonitor` creates no PodMonitor, so all nine Postgres
  alerts — `PostgresInstanceDown` included — matched an empty vector forever. Measured 2026-08-30:
  4 of 62 declared clusters, two of them the party and identity golden-record databases, both
  backing up to S3 with that backup state watched by nothing. **The fix is a denominator that
  exists whether or not the subject does**: a constant `vector(1)` recording rule per declared
  subject, `unless on(...)` the set derived from real `up`, so absence is a positive series. Derive
  the constant series from the manifests and generate the rule — a hand-kept expected-list is the
  same defect one layer up (`check-cnpg-scrape-coverage.py`, #7220).
- **Two promtool traps that make a unit test pass while measuring nothing.** Both were live in the
  first draft of `openbank-infra/tests/promtool/postgres_threshold_alerts_test.yaml`:
  - **`time()` starts at the unix epoch.** A sentinel-zero guard (`... and metric > 0`, which
    exists because `time() - 0` is ~56 years on a real cluster) tested at `eval_time: 45m` sees
    `time() - 0 = 45 minutes` — under every threshold, so the case passes with the guard DELETED.
    Push `eval_time` past the threshold (33h here) and give the series enough samples to reach it.
  - **An input `interval:` wider than the 5m staleness window makes `for:` unreachable.** At
    `interval: 10m` the series is stale for half of every gap, the condition flickers between
    evaluations, and a `for: 30m` never matures — so the alert reads as "does not fire" for a
    reason that has nothing to do with the rule. Keep test intervals at 1m.
  Falsify every case by deleting the clause it covers and confirming it goes red; `promtool check
  rules` proves a rule PARSES and has been green through every alerting defect recorded here.

- **A recording rule's `interval` decides whether its output EXISTS for its consumers, not just
  how fresh it is.** Prometheus answers an instant query from a 5-minute lookback window, so a
  group at `interval: 1h` is resolvable for 5 of every 60 minutes — a 1-in-12 duty cycle.
  `openbank.ai.price-book` ran hourly while `openbank.ai.spend` joined against it every 5m, so 11
  of every 12 spend evaluations saw no price: `openbank:llm_cost_usd_24h:total` had NO SERIES,
  `AiFleetDailySpendHigh` (the 25 USD/day ceiling) could not fire, and `AiSpendUnpriced` fired for
  a model that WAS priced — `unless on (model)` against a stale price series reports everything as
  unpriced, so the check meant to prove the price book complete was reporting the lookback window
  instead. Measured at the group's own `lastEvaluation`: 9 series at eval+60s, 0 at eval+600s
  (#6151). For a `vector(<constant>)` rule the interval is not a cost knob — evaluation is free.
- **An agent's own metrics `interval` is how often it RECOMPUTES, not how often you scrape — and
  the two are set in different files.** Falco's chart defaults to `metrics.interval: 1h`, so a 30s
  ServiceMonitor returns the same hour-old numbers 120 times and every gauge lags reality by up to
  an hour, with nothing anywhere looking broken. Set it to `1m` (#6322) and prove it by EFFECT, not
  by reading the config back: `falcosecurity_falco_duration_seconds_total` grew by exactly 90.0s
  over 90s of wall clock; at the default it would not have moved. Same family as the recording-rule
  bullet above, one layer further out.
- **A metric name is an unverified claim about someone else's build.** Falco's prefix is
  `falcosecurity_`, NOT `falco_` — a probe written against the obvious guess reported zero for ten
  minutes against a perfectly working agent serving 44 metrics on 12 scrape targets, all `up`. The
  zero is indistinguishable from "the feature did not work". Read the names off the running
  endpoint (`kubectl exec <pod> -- curl -s localhost:<port>/metrics`) or off
  `/api/v1/label/__name__/values` before writing any rule, and when a probe returns nothing, check
  the SCRAPE TARGETS before believing it.
- **`loki.ruler` is not a chart key; it is `loki.rulerConfig`.** Helm drops unknown keys silently,
  so a detailed, well-commented ruler block can sit in git while the deployed config has
  `alertmanager_url` EMPTY and `storage.type: s3` instead of the sidecar's local directory. Three
  rule ConfigMaps (`falco-runtime`, `endpoint-availability`, `silent-failures`) had never been
  evaluated — `/prometheus/api/v1/rules` returned `groups: []` — and even a loaded rule would have
  been delivered nowhere (#6032). Two plausible hypotheses that are NOT the cause, so nobody
  re-runs them: restarting Loki does not fix it, and the rules volume IS shared with the loki
  container (the sidecar writes `/rules/fake/*.yaml` and `ls` from either container shows them).
  **Read `GET /config` on the pod and diff it against the values file** — that is the only probe
  that distinguishes "configured" from "in effect".

### A gate can run, print a healthy subject count, and have measured nothing

- **A history-dependent gate must state which history it got.** `check-flyway-version-commit-order`
  needed three attempts, and each intermediate version RAN and returned a confident verdict: first
  from an empty order map (`origin/main` does not resolve on a PR checkout), then from the wrong
  history (implicit HEAD is the PR merge commit, so it measured "order including this PR"), then
  from a truncated one — **the gates shard checks out at `fetch-depth: 1`, and on a shallow repo
  `git log --diff-filter=A` reports EVERY file as added at the graft point**, so all 355 migrations
  shared one position, the per-service sort fell through to its version tiebreak, everything looked
  monotonic and the gate was green. It printed `355 migrations ordered` throughout. Detect
  shallowness (`git rev-parse --is-shallow-repository`), deepen in place rather than making the
  whole shard pay `fetch-depth: 0`, pick the LONGEST candidate ref rather than the first that
  answers, and refuse to report when the history is shorter than the corpus.
- **Only the both-ways baseline caught all three.** With no violations found, every baselined entry
  reads as stale, which is the one observable that separates "clean" from "measured nothing". A
  baseline that can only be checked in the violation direction cannot do this — it is the whole
  argument for the idiom, and it caught the checker rather than the corpus three times running.
