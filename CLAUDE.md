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

### Flyway migrations
- **Never change a migration after it is applied to a live DB.** Rewriting V10 (CONCURRENTLY →
  plain) caused `FlywayValidateException: checksum mismatch` → startup fail. Fix:
  `QUARKUS_FLYWAY_REPAIR_AT_START=true` in gitops env, then remove once DB is settled.

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

### 2-dot vs 3-dot git diff
- **Always 3-dot for pre-merge review.** `git diff origin/main...origin/branch` (3-dot) = actual
  squash delta. 2-dot includes main's post-divergence commits → makes stale branches look like
  regressions → fed false NO-GO to Sonnet twice in one session.

## Where things are

- Rules (authoritative): `openbank-libs/governance/rules.yaml`
- Architecture decisions: `docs/adr/` (start at 0001; governance is 0029/0030)
- Shared runtime plumbing: `openbank-libs/src/main/kotlin/com/openbank/libs/` (`web/ServiceInfoResource`,
  `web/ApiVersionResponseFilter`, security, audit, outbox, idempotency)
- Per-service specifics: that service's own `CLAUDE.md`.
