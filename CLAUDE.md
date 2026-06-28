# OpenBank — agent & contributor guide

Banking-grade Quarkus/Kotlin monorepo: ~30 `openbank-*-service` microservices, a Next.js
`openbank-admin-ui`, and a shared `openbank-libs`. Hexagonal architecture per service (ADR-0002).

**Authoritative rules live in [`openbank-libs/governance/rules.yaml`](openbank-libs/governance/rules.yaml).**
This file is the human summary; `rules.yaml` is what CI enforces. When they disagree, `rules.yaml` wins.
`CONTRIBUTING.md` is the long-form narrative.

## The non-negotiables (this is what gets forgotten — don't)

Before you consider a change "done", run the ship-checklist — `/ship-check` runs these exact checks, the
same ones CI gates on (ADR-0029). Failing any of these is the usual cause of repeated mistakes:

1. **Open a PR.** No direct commits to `main`. Branch `<type>/<scope>-<summary>`; squash-merge via PR.
2. **Bump the service version.** Any change under `<service>/src/main/**` ⇒ bump `<service>/version.txt`
   per commit type (`feat`→minor, `fix`/`perf`→patch, `BREAKING CHANGE`→major). Per-service, independent.
3. **Changelog & release are automatic** via release-please from Conventional Commits — so the commit
   message *is* the changelog. `feat`/`fix`/`perf`/`security` must be conventionally formatted or they
   vanish from release notes. Never hand-write a release version, `CHANGELOG.md`, or Release: merge to
   `main` opens a per-service Release PR; merging *that* bumps `version.txt`, writes the changelog and
   tags `<component>-v<version>`. release-please owns the **release axis only** — it does **not** touch
   `openapi.yaml:info.version` (that is the separate API-contract axis, ADR-0048). The rule:
   [`openbank-libs/governance/RELEASE.md`](openbank-libs/governance/RELEASE.md). A module is a released
   component **iff it has a `version.txt`**; to add one, create it and list it in
   [`release-please-config.json`](release-please-config.json) + [`.release-please-manifest.json`](.release-please-manifest.json).
4. **API change ⇒ `openapi.yaml` updated + contract test.** Two version axes (ADR-0048): the **release**
   version (`version.txt`) and the **API contract** version (`openapi.yaml:info.version`, whose `major`
   == `openbank.api.version` == URL `/api/v{N}`). They are independent — an API change classifies its own
   bump from the OpenAPI diff (`oasdiff`), not from the commit type, and must **not** be forced equal to
   the release version. (`X-API-Version`/`X-Service-Version` and `/api/v1/info` are served by `openbank-libs`.)
5. **DB change ⇒ Flyway migration + rollback note. Event change ⇒ schema versioned backward-compatibly.**
   **Config change ⇒ no duplicate YAML keys in `application.yaml`.** SmallRye Config / SnakeYAML do
   **not** merge a repeated mapping key — the *last* wins and earlier ones are silently dropped to
   library defaults (a boot smoke-test won't catch it). CI flags any duplicate key at any level
   (`.github/scripts/check-duplicate-yaml-keys.sh`, **enforced** — the #1193 sweep is complete and the
   fleet reports 0); this guards the defect class behind #1170 (dup `quarkus.http` dropped the port) and
   #1193 (dup top-level `openbank:` dropped rate-limit/resilience).
6. **Test the new behavior.** Coverage is ratchet-only (never lower). Money-path services aim higher.
7. **Catalog & coverage are derived, never hand-edited.** Do **not** edit
   `openbank-admin-ui/src/lib/governance/manifest.ts` by hand for versions/lineage — that data is being
   moved to CI-generated `service-graph.json` / `catalog.json` (ADR-0029 D3). Hand-edits rot.
8. **Money-path services** (see `rules.yaml: money_path_services`) need 2 approvals + a threat model
   (`docs/threat-models/<service>.md`, ADR-0030).

## Commit format

```
<type>(<scope>): <imperative summary>
```
`type` ∈ feat|fix|perf|refactor|docs|test|chore|build|ci|security. `scope` = service without
`openbank-` prefix (e.g. `ledger`, `sepa-payment`) — **the scope selects the released component**.
Sign every commit: `git commit -s -S`. Full vocabulary in `rules.yaml: commits`.

## Issues — the actionable backlog (ADR-0052)

Issues track *what needs doing*; they do **not** duplicate ADRs (decisions) or release-please
(changelog). Open one for: a **fleet sweep** (cross-service change, one PR per service), a
**governance follow-up** (the actionable tail of an ADR — a pending rollout, a regulatory
go-live condition, a gate to flip), a **bug**, or an **enhancement**. Not for: architectural
decisions (→ `docs/adr`), questions (→ Discussions), or security holes (→ private Security
Advisories). Templates live in `.github/ISSUE_TEMPLATE/`; authoritative rules in
`rules.yaml: issues`.

- **Link the PR.** Every PR fills the template's "Linked issues": `Closes #<n>` (auto-closes
  on merge) or, for one service in a sweep, `Refs #<n>` (leaves the tracker open).
- **Labels are code.** The label set is `​.github/labels.yml`, applied by the `Label sync`
  workflow — additive, never pruned. Do **not** create labels in the UI (rule #7: derived
  data is never hand-edited). A money-path issue gets the `money-path` label ⇒ each resulting
  PR still needs 2 approvals + threat model.

## Build

```
./gradlew :<module>:build      # one service, e.g. :openbank-ledger-service:build
./gradlew detekt ktlintCheck koverVerify build   # the local gate before a PR
```
CI is path-scoped (only changed services build). Domain layer has **zero** framework imports.

## Skills (run the recurring workflow instead of re-deriving it)

- `/ship-check` — authoritative pre-merge preflight; mirrors the CI gates.
- `/bump <service>` — raise version.txt + openapi.yaml info.version per change type.
- `/open-pr` — branch, PR from template, verify bump + changelog present.
- `/release <service>` — assemble release notes (release-please).

## GitOps & deploy rules (learned the hard way — don't repeat)

### Service image builds
- **Always fast-jar, never uber-jar.** All service Dockerfiles use
  `-Dquarkus.package.jar.type=fast-jar`; the runtime stage COPYs `quarkus-app/`.
  The deprecated `-Dquarkus.package.type=uber-jar` leaves `quarkus-app/` empty → crashlooping pod.
  Generic build: `openbank-infra/scripts/build-push-service.sh <service>`.
- **Host-side build, not in-Docker Gradle.** `build-push-service.sh` runs `quarkusBuild` locally
  first; in-image Gradle hits download timeouts and can't find sub-project dirs.
- **Dirty worktree = corrupted image.** Before `build-push-service.sh`, verify `git status src/main/`
  is clean. A second parallel agent can leave uncommitted edits that get baked into the image silently.
- **Stale `build/quarkus-app/` = ClassNotFoundException at boot.** Delete `build/quarkus-app/` and
  `build/classes/` before re-building if the previous build was interrupted or from a different branch.
- **Quarkus CDI wiring is NOT validated by `ktlintCheck` + unit tests.** Add `:svc:quarkusBuild`
  (no Docker) to the pre-push gate; ArC/CDI failures only surface at that step, not in `./gradlew test`.
- **Never-deployed service = latent boot defects.** A service that passes unit tests but has never
  booted against a real container stack silently accumulates config/CDI bugs. Add a Testcontainers
  boot smoke-test (`@QuarkusTest` + `management.enabled=false`) before first deploy.
- **Local JDK version mismatch.** Mac default JDK may be JDK 26; the Gradle toolchain is pinned to 25.
  mockk/Objenesis fails with `ObjenesisException` on JDK 26. Fix: `export JAVA_HOME=$(path to temurin-25)`
  before running Gradle. Symptom: tests that pass in CI fail locally with reflection errors.
- **Two local Gradle instances stomp each other.** A second `./gradlew` on the same `GRADLE_USER_HOME`
  stops the first daemon and corrupts the local `~/.gradle` cache. Isolate with
  `GRADLE_USER_HOME=/tmp/gradle-isolated-$$ ./gradlew ...`.
- **`quarkusBuild` produces `quarkus-app/lib/` with 600 permissions.** Non-root user inside the
  container cannot open the JARs → `ClassNotFoundException` at boot. `build-push-service.sh` contains
  the fix (`chmod -R a+r`); if you run `quarkusBuild` manually and then `docker buildx`, add the chmod
  before the docker step.
- **Parallel Docker/Gradle builds OOM on 16 GB Docker Desktop.** Running N Quarkus builds concurrently
  multiplies heap usage; the Docker VM gets OOM-killed silently. Run service builds sequentially with a
  60–90 s gap, or increase Docker Desktop memory to 24 GB+.
- **admin-ui does NOT go through `auto-deploy.yml`.** Next.js has no `quarkusBuild`; the standard
  auto-deploy pipeline skips or errors on it. Deploy admin-ui manually:
  `openbank-infra/scripts/build-push-admin-ui.sh` from a clean worktree with `AWS_PROFILE=openbank`.

### Kotlin / Quarkus code pitfalls
- **Kotlin JUnit5 + `runBlocking` silent test drop.** `fun foo() = runBlocking { }` infers return
  type `T` (not `Unit`) → JUnit5 silently ignores the method (not a `void` test). Always write
  `fun foo(): Unit = runBlocking { }` or use `suspend` + coroutine test runner.
- **`@ConfigProperty` optional field must be `Optional<String>`, not `String`.** A missing optional
  property typed as plain `String` throws `SRCFG00040` at boot. Use `Optional<String>` + `defaultValue`
  or accept `Optional.empty()`. Symptom: `@QuarkusTest` boots fine locally (env set) but fails in CI.
- **`openbank.outbox.dispatch-enabled` defaults to `false`.** A new service that uses outbox but
  omits this key silently never dispatches events — no error, no log, `attempt_count` stays 0.
  Every service with an outbox entity MUST have `openbank.outbox.dispatch-enabled: true` in
  `application.yaml`.
- **ktlint wildcard imports surface on first edit.** Path-scoped CI only lints changed files; pre-existing
  wildcard imports in a file are invisible until you touch it. When editing an older `.kt` file, manually
  expand any wildcards in the import block — `ktlintFormat` is unreliable for this.

### Flyway migrations
- **Never change a migration after it is applied to a live DB.** Rewriting V10 (CONCURRENTLY →
  plain) caused `FlywayValidateException: checksum mismatch` → startup fail. Fix:
  `QUARKUS_FLYWAY_REPAIR_AT_START=true` in gitops env, then remove once DB is settled.

### Kubernetes / ArgoCD pitfalls
- **`strategy.type: Recreate` with Server-Side Apply = HTTP 403 Forbidden.** SSA merges the
  defaulted `rollingUpdate` sub-block even when `type: Recreate`; the API rejects the combination.
  Use `RollingUpdate` with `maxSurge: 0 / maxUnavailable: 1` — identical zero-concurrency behaviour,
  cleanly SSA-mergeable.
- **DaemonSet node-agents on bin-packed nodes need `system-node-critical` priority.** On a
  resource-tight sandbox node, a DaemonSet pod without `priorityClassName: system-node-critical`
  gets `Insufficient cpu` or `Too many pods`. Set `priorityClassName` from the start; fix the pod
  spec (not the node provisioning) — Karpenter does not provision new nodes solely for DaemonSet pods.
- **Temporal app-plane workers need explicit `frontend` NetworkPolicy allowlisting.** `AppWorkerRegistrar`
  connects to Temporal frontend via gRPC `:7233` synchronously at startup — a boot fail, not a
  runtime error. The `temporal-platform-ingress` NetworkPolicy must explicitly list every
  application namespace that runs Temporal workers, not just `temporal-platform`.
- **CNPG DaemonSet + broad `podSelector: {}` = operator deadlock.** A DaemonSet NetworkPolicy with
  an empty pod selector captures CNPG pods in the same namespace → operator status extraction
  hangs → cluster health check stalls. Always allow `cnpg-system → db:8000,9187` in the NP.
- **OpenBao recovery keys must be stored externally at init time.** After a Vault→OpenBao migration
  the root token is revoked and recovery keys are the only break-glass. If they are lost (not saved
  to AWS Secrets Manager `openbank/openbao/break-glass`) the cluster is unrecoverable without a
  full re-init. Save them immediately after `openbao operator init`.

### GitOps merge conflicts — image tags
- **NEVER `git checkout --theirs` blindly for image tags.** `--theirs` (main) may have an older
  image from the parallel instance; `--ours` (branch) has the freshly-built one. Blind `--theirs`
  caused a regression from v0.11.1→v0.9.0 (admin-ui) and a crashlooping account-service. For
  image lines take `--ours`; for RBAC/config/env take `--theirs` or resolve manually.

### Verified signatures (main-protection ruleset)
- **Commit email must match the registered GPG key.** The GPG key is for `jiri@iraska.cz`;
  commits with `jiri@iraska.cz` are `no_user` → unverified → merge blocked by ruleset.
  Global config: `git config --global user.email "jiri@iraska.cz"`. Re-sign:
  `git commit --amend --reset-author --no-edit -S`.

### Multi-agent / parallel work
- **Shared worktree = branch stomping.** Two agent instances on the same working tree overwrite each
  other's branch refs. Always do PR work in an isolated `git worktree add /tmp/wt-<name> -b <branch>`.
  Use a **unique suffix** per agent: `/private/tmp/ob-<task>-$$` — generic names like `wt-fix` collide.
- **Verify branch before commit.** A parallel agent can switch the branch in the shared tree mid-build.
  Run `git branch --show-current` immediately before `git commit`, and push with an explicit refspec
  (`git push origin HEAD:refs/heads/<branch>`) to avoid landing on the wrong branch.
- **NEVER `git add -A` or `git add .` in a shared worktree.** These pick up the other agent's WIP
  files. Stage an explicit file list: `git add path/to/file1 path/to/file2`.
- **Recovering a stomped branch: use `git reflog`.** If a second agent reset your branch ref, the
  commit survives in the reflog. `git reflog | grep <sha-prefix>`, then
  `git branch -f <branch> <sha>` — no destructive `reset --hard` needed.
- **Tool feedback after Edit/Write can be stale.** In multi-agent sessions, after writing a file in the
  main worktree confirm the change persisted with a `grep` Bash call — another agent writing the same
  file concurrently can silently overwrite it. Prefer isolated worktrees for any concurrent work.
- **Auto-deploy concurrency: second merge cancels first deploy.** Two merges in quick succession →
  the later push cancels the earlier deploy workflow. If two services merge within seconds, re-dispatch
  manually: `gh workflow run auto-deploy.yml -f services=<svc>`.
- **chain-branch pollution.** A branch cut from a feature branch (not `main`) carries the feature's
  uncommitted diff. Always branch from `main` or cherry-pick specific commits.

### GHA / CI pitfalls
- **`env.VAR` is unavailable in job-level `env:` inside a reusable workflow.** The `env` context is
  not resolved at parse time for called workflows — use a setup step that writes to `$GITHUB_ENV`
  instead. This caused a 30-hour CI outage (PR #1888).
- **`gen-network-policies.py` ignores `@ConfigProperty` code defaults.** The generator reads only
  gitops env declarations. A port defined only as a code default is missing from the generated
  NetworkPolicy and gets silently blocked. Declare every port as an explicit SmallRye env var.

### 2-dot vs 3-dot git diff
- **Always 3-dot for pre-merge review.** `git diff origin/main...origin/branch` (3-dot) = actual
  squash delta. 2-dot includes main's post-divergence commits → makes stale branches look like
  regressions → fed false NO-GO to Sonnet twice in one session.

### CI/CD runner fleet — FinOps order (NEVER violate)
- **Primary: Hetzner (x86) + Mac mini (ARM) — cheap, always-on, zero AWS cost.**
  Everything that fits runs here first. `openbank-build` and `openbank-batch` labels on both.
- **Secondary: ARC on AWS Spot — overflow only.** `minRunners=0` (scale-to-zero); idle cost $0.
  ARC handles bursts (full-fleet rebuild, parallel PR storm) that exceed Hetzner+Mac capacity.
  It is NEVER the primary build target. Do not change `arc_min_runners` > 0 without a measured
  SLO miss and explicit owner approval.
- **If a build fails on Hetzner due to a platform/arch issue → fix the root cause, never remove
  Hetzner from the label pool.** Removing `openbank-build` from Hetzner silently routes all
  builds to expensive ARC. The correct fix for an arm64 Docker build crashing on x86 is
  `--platform=$BUILDPLATFORM` on build-only stages (deps/compile); the runtime stage inherits
  `--platform linux/arm64` from `docker buildx build --platform`. JS/JVM build artefacts are
  arch-agnostic and safe to copy into the arm64 runtime stage.
- **Hetzner ARM (CAX) migration is pending** — Hetzner CAX capacity was exhausted on 2026-06-28;
  monitor and migrate CPX42→CAX31 when available (~40% cost saving + native arm64).

## Where things are

- Rules (authoritative): `openbank-libs/governance/rules.yaml`
- Architecture decisions: `docs/adr/` (start at 0001; governance is 0029/0030)
- Shared runtime plumbing: `openbank-libs/src/main/kotlin/com/openbank/libs/` (`web/ServiceInfoResource`,
  `web/ApiVersionResponseFilter`, security, audit, outbox, idempotency)
- Per-service specifics: that service's own `CLAUDE.md`.
