# OpenBank — agent & contributor guide

A banking platform reference implementation: a Quarkus/Kotlin monorepo of ~34 `openbank-*`
microservices, a Next.js `openbank-admin-ui`, and a shared `openbank-libs`. Hexagonal
architecture per service (ADR-0002).

**Authoritative rules live in [`openbank-libs/governance/rules.yaml`](openbank-libs/governance/rules.yaml).**
This file is the human-readable summary; `rules.yaml` is what CI enforces — when they disagree,
`rules.yaml` wins. [`CONTRIBUTING.md`](CONTRIBUTING.md) is the long-form narrative.

## The non-negotiables

Before a change is "done", run the ship-checklist (`/ship-check` runs the same checks CI gates on,
ADR-0029):

1. **Open a PR.** No direct commits to `main`. Branch `<type>/<scope>-<summary>`; squash-merge via PR.
2. **Versioning is automatic.** A change under `<service>/src/main/**` is released by **release-please**
   from your Conventional Commit — so the commit message *is* the changelog. Do **not** hand-edit
   `version.txt`, `CHANGELOG.md`, or `quarkus.application.version` (it derives from `version.txt`).
   A module is a released component **iff** it has a `version.txt` (registered in
   [`release-please-config.json`](release-please-config.json) + [`.release-please-manifest.json`](.release-please-manifest.json)).
3. **API change ⇒ `openapi.yaml` updated + contract test.** Two independent version axes (ADR-0048):
   the **release** version (`version.txt`) and the **API-contract** version
   (`openapi.yaml:info.version`, whose major == URL `/api/v{N}`). An API change classifies its own bump
   from the OpenAPI diff (`oasdiff`), never forced equal to the release version.
4. **DB change ⇒ Flyway migration + rollback note. Event change ⇒ schema versioned backward-compatibly.**
   **Config change ⇒ no duplicate YAML keys** in `application.yaml` — SmallRye/SnakeYAML keep only the
   *last* of a repeated mapping key and silently drop the rest (CI enforces this).
5. **Test the new behavior.** Coverage is ratchet-only (never lower); money-path services aim higher.
6. **Derived data is never hand-edited.** Catalog, coverage, and the governance manifest are
   CI-generated — edit the source, not the artifact.
7. **Money-path services** (`rules.yaml: money_path_services`) need 2 approvals + a threat model
   (`docs/threat-models/<service>.md`, ADR-0030).

## Commit format

```
<type>(<scope>): <imperative summary>
```
`type` ∈ feat|fix|perf|refactor|docs|test|chore|build|ci|security. `scope` = service without the
`openbank-` prefix (e.g. `ledger`, `sepa-payment`) — **the scope selects the released component**.
Sign every commit: `git commit -s -S`. Full vocabulary in `rules.yaml: commits`.

## Issues — the actionable backlog (ADR-0052)

Issues track *what needs doing*; they don't duplicate ADRs (decisions) or release-please (changelog).
Open one for a **fleet sweep**, a **governance follow-up** (the actionable tail of an ADR), a **bug**,
or an **enhancement** — not for architectural decisions (→ `docs/adr`), questions (→ Discussions), or
security holes (→ private Security Advisories). Every PR links its issue (`Closes #<n>` / `Refs #<n>`).
Labels are code (`.github/labels.yml`, applied by the Label-sync workflow) — don't create them by hand.

## Build

```
./gradlew :<module>:build                          # one service
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```
CI is path-scoped (only changed services build). The domain layer has **zero** framework imports.

## Skills

- `/ship-check` — authoritative pre-merge preflight; mirrors the CI gates.
- `/bump <service>` — raise `version.txt` + `openapi.yaml` info.version per change type.
- `/open-pr` — branch, PR from template, verify bump + changelog present.
- `/release <service>` — assemble release notes (release-please).

## Engineering notes (common pitfalls)

These are real, repeatable gotchas — worth knowing before they cost you a debugging session.

### Kotlin / Quarkus
- **Always fast-jar, never uber-jar.** Service Dockerfiles use `-Dquarkus.package.jar.type=fast-jar`
  and COPY `quarkus-app/`; an uber-jar leaves `quarkus-app/` empty → crashlooping pod.
- **`@ConfigProperty` optional fields must be `Optional<String>`,** not plain `String`, or a missing
  value throws `SRCFG00040` at boot. Use `Optional<String>` + `defaultValue`.
- **Kotlin JUnit5 + `runBlocking` silent drop.** `fun foo() = runBlocking { }` infers a non-`Unit`
  return type and JUnit5 ignores the method. Write `fun foo(): Unit = runBlocking { }` or use a
  coroutine test runner.
- **`openbank.outbox.dispatch-enabled` defaults to `false`.** Any service with an outbox entity must
  set it `true` in `application.yaml`, or events never dispatch (no error, `attempt_count` stays 0).
- **CDI wiring isn't validated by `ktlintCheck` + unit tests.** Add `:svc:quarkusBuild` to your
  pre-push gate; ArC/CDI failures only surface there.

### ktlint
- Path-scoped CI only lints changed files, so a pre-existing wildcard import or a latent
  `function-signature` violation surfaces the first time you touch an older file. Let `ktlintFormat`
  collapse multi-line signatures; expand wildcard imports rather than hand-wrapping.

### Flyway
- **Never change a migration after it has been applied to a live DB** — Flyway checksums the whole
  file (comments included), so any edit triggers a `checksum mismatch` startup failure.

### Contract tests (Pact)
- **One `@Provider` test per provider.** Two provider-verification classes with the same `@Provider`
  both pull every pact the broker holds and collide; use a single test that picks the target per
  interaction in `@BeforeEach`. Provider tests are gated on `pactbroker.url` and run only in CI.

### OPA / authorization
- **`input.principal.type == "SERVICE"` can never fire — don't write it.** `AuthorizeInterceptor`
  only ever emits `ANONYMOUS`/`AI_AGENT`/`HUMAN`; M2M callers authenticate with a Keycloak
  client_credentials JWT, which the interceptor classifies as `HUMAN`, and no realm client is ever
  granted `ROLE_SERVICE`. A rego rule gated on a `SERVICE` principal type is structurally
  unreachable dead code that silently denies its intended M2M caller once `AUTHZ_ENFORCE` flips to
  `true` (found live in the shared `rest.rego` `edge-service-notification` rule, ADR-0034 Phase 5,
  issue #266). Identify a specific M2M caller by `input.principal.id` (Keycloak's
  `service-account-<clientId>` convention) instead — gating on `HUMAN` + `ROLE_OPERATOR` alone is
  NOT equivalent, since real staff also carry `ROLE_OPERATOR` and would over-grant. Enforced by
  `.github/scripts/check-no-service-principal-type.sh` (`rules.yaml: authz_policy`).

### GitOps / Kubernetes
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
  Verify with `cosign verify-attestation` after attesting; never trust `attest` exit 0 alone.

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

### Reviewing a diff
- **Use 3-dot diff for pre-merge review:** `git diff origin/main...origin/<branch>` is the actual
  squash delta; 2-dot includes main's post-divergence commits and makes stale branches look like
  regressions.

### API contract (ADR-0048)
- **Two racing spec PRs can both claim the same `info.version` — and both pass the gate.** The
  api-contract gate classifies against the PR's *creation-time* base
  (`github.event.pull_request.base.sha`), so a competing bump that merges first is invisible to
  the second PR: it lands with new endpoints under an unchanged version (#481 vs #524 on the
  ledger spec, corrected by #534). After any competing `openapi.yaml` change merges — including
  when you resolve a merge conflict against `main` — re-check `info.version` against the *current*
  `main` and re-bump; whoever lands second takes the next version. A matching version line merging
  "cleanly" is the trap: git sees identical text, not a taken version.

## Capturing what we learn

A corrected non-obvious mistake or a hard-won lesson belongs **in the repo**, where every contributor
reads it. Route each by kind (authoritative spec: `rules.yaml: knowledge_capture`):
- **Operational footgun** → a one-line bullet in the matching section above, or the relevant
  `<service>/CLAUDE.md`: symptom + fix, imperative.
- **A hard, checkable rule** → encode it in `rules.yaml` and, where feasible, a CI guard, so it is
  *enforced* not merely documented. Summarize the human-readable form here.
- **A recurring workflow** → a skill under `.claude/skills/`.

Always act on the authoritative source on `origin/main` — not a local snapshot or cached page. The
repo is the single source of truth.

## Where things are

- Rules (authoritative): `openbank-libs/governance/rules.yaml`
- Architecture decisions: `docs/adr/` (start at 0001; governance is 0029/0030)
- Shared runtime plumbing (ADR-0122 domain/runtime split): pure domain logic —
  security, audit envelope, outbox ports, idempotency store — lives in
  `openbank-libs-domain/src/main/kotlin/com/openbank/libs/`; framework-touching
  code — `web/ServiceInfoResource`, audit publisher, outbox dispatchers,
  idempotency impl — lives in `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/`.
- Per-service specifics: that service's own `CLAUDE.md`.
