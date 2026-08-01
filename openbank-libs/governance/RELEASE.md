<!-- SPDX-License-Identifier: Apache-2.0 -->
# Release & changelog rule (ADR-0029 Layer B, refined by ADR-0048)

**This is the rule. It is enforced by machine, not by discipline.** Release versions, changelogs and
GitHub Releases are produced by [release-please](https://github.com/googleapis/release-please) from
merged Conventional Commits. Nobody hand-writes a version, a `CHANGELOG.md`, or a Release. The machine
source of truth is [`rules.yaml: versioning`](./rules.yaml); this file is the human explanation.

## Two version axes — do not conflate them (ADR-0048)

A service carries **two independent** SemVers that move on different cadences:

| Axis | What it is | Source of truth | Who moves it |
|------|-----------|-----------------|--------------|
| **Release version** | the deployable artifact's version | `version.txt` | **release-please** (this rule) |
| **API contract version** | the public REST contract (`major` lives in the URL `/api/v{N}`) | `openapi.yaml:info.version` + `openbank.api.version` | the **API change** itself, classified by `oasdiff` (ADR-0048) |

**release-please owns the RELEASE axis only.** It never touches `openapi.yaml:info.version` — that
number is the API contract axis and moves only when the REST contract changes. A `fix` that bumps the
release version must *not* drag the API version with it.

## The release-axis rule in one paragraph

Every module that ships is a **released component iff it has a `version.txt`** (today: 23 services).
You write a Conventional Commit; you bump nothing by hand. On merge to `main`, release-please opens
**one aggregated Release PR** (`chore(main): release main`) holding every component with pending
changes, each proposing its own next SemVer (from commit types) and its own assembled release notes.
When **you merge that Release PR**, release-please bumps each `version.txt`, writes each per-service
`CHANGELOG.md`, and cuts one tagged GitHub Release `<component>-v<version>` per component.

Versioning stays fully per-component — only the *pull request* is shared. It was per-PR until
2026-08-01 (`separate-pull-requests: true`), which produced 194 release PRs in one week, 26% of every
PR merged, each carrying the full required-check set. Aggregating also removes a whole conflict class:
parallel release PRs all edit `.release-please-manifest.json`, and the bot will not rebase a conflict
another component caused, so they had to be drained by hand.

## Commit type → release bump (must match [`rules.yaml: commits`](./rules.yaml))

| Commit type | Release bump | In release notes? |
|-------------|--------------|-------------------|
| `feat`                    | minor | **yes** — *Features* |
| `fix`                     | patch | **yes** — *Bug Fixes* |
| `perf`                    | patch | **yes** — *Performance* |
| `security`                | patch | **yes** — *Security* |
| `BREAKING CHANGE:` / `!`  | major | yes, under its type |
| `refactor` `docs` `test` `build` `ci` `chore` | none | hidden |

So: **if it isn't a `feat`/`fix`/`perf`/`security` commit, it will not appear in release notes** — the
commit message *is* the changelog. release-please attributes a commit to a component by the **files it
touches** under that component's directory, regardless of the typed scope.

**This means a `feat`/`fix`/`perf`/`security` PR that only touches a service directory *outside*
`<service>/src/main/**`** — an `openapi.yaml`-only edit, a gitops/docs-only PR that happens to also
add a file inside the service dir — **still proposes a release**, even though
`rules.yaml: change_requirements.release_scope_mismatch` says only `src/main/**` ought to.
release-please sees "a file under this component's directory changed" + "what type is the commit";
it has no include/allow-list, so "only `src/main` releases" is not expressible.

What it *does* have is `exclude-paths` (per package, added in #1277): every package now excludes its
own `src/test`, so a `src/test/**`-only change no longer proposes a release whatever its type.
Exclusion matches **directory prefixes only** (`file.indexOf(path + '/') === 0`) — a single file
cannot be excluded, and a commit touching `src/main` *and* `src/test` still releases (a commit is
skipped only if **all** its files match an exclude).

The gap surfaced live in PR #547/#551 (a `src/test/**`-only boot-smoke test inside a
gitops-registration PR proposed `finrep-service 0.4.0` for an artifact that hadn't changed) — the
case `exclude-paths` now covers. For the paths it doesn't cover the noise remains, so
`check-release-scope-mismatch.py` flags it as an advisory PR-time warning
(`rules.yaml: change_requirements.release_scope_mismatch`). When it fires and the PR genuinely doesn't
change the service's shipped code, re-type the commit (`test:`/`docs:`/`chore:`/`refactor:`/`build:`/
`ci:`) rather than editing any release-please output by hand.

## The release_invariant (release-please + the build keep it true)

For a released service these MUST be equal (`rules.yaml: versioning.release_invariant`):

- `version.txt` — source of truth; release-please bumps it.
- `quarkus.application.version` — `build.gradle.kts` reads `version.txt` at build time. Automatic.
- git tag `<component>-v<version>` — cut by release-please.
- container image tag — stamped from `version.txt` at image build (CI).

The **API axis** is governed separately: `major(openapi.yaml:info.version) == openbank.api.version ==
URL /api/v{N}` (`rules.yaml: versioning.api_invariant`), and an API change classifies its own bump from
the OpenAPI diff via `oasdiff` (ADR-0048). Do **not** edit `openapi.yaml:info.version` to "do a
release" — that conflates the two axes ADR-0048 split.

## What is wired vs. still planned

- **Wired (this layer):** per-service release bump, per-service `CHANGELOG.md`, GitHub Release,
  baseline at `last-release-sha`. `build.gradle.kts` reads `version.txt` for every service.
- **Wired (evidence bundle — assembled & signed):** when a Release PR merge cuts a tag,
  `release-please.yml`'s `release-evidence` job assembles the per-component **evidence bundle** and
  attaches it to the GitHub Release, each document signed with the AWS KMS cosign key
  (`awskms:///alias/openbank-cosign-signing`, the same key as image signing). Per `<tag>`:
  - `<tag>.cdx.json` — **CycloneDX SBOM** (Gradle cyclonedx plugin), *attached, not orphaned*
    (`rules.yaml: provenance.sbom`);
  - `<tag>.slsa.json` — **SLSA v1.0 provenance** (in-toto statement; subject = the SBOM, materials =
    the git commit, builder = the release workflow);
  - `<tag>.vex.json` — **OpenVEX 0.2.0**: a `trivy sbom` inventory (every finding `under_investigation`,
    never an auto-asserted `not_affected`) **merged with the human triage store**
    [`openbank-libs/governance/vex/<component>.openvex.json`](vex/) — a reviewed `not_affected`/`fixed`
    verdict there wins per CVE;
  - `<tag>.evidence.json` — the **signed manifest** tying it together: version, git commit, SBOM/SLSA/VEX
    digests + signatures, changelog excerpt, AI attribution (authors + `Co-Authored-By` since the previous
    component tag, ADR-0031), and references to scan/coverage/test results on the producing CI run;
  - plus a `.sig` per document.

  Assembly logic: [`.github/scripts/build-release-evidence.sh`](../../../.github/scripts/build-release-evidence.sh).
  **Non-blocking in v1** (`continue-on-error`): the release/tag are already cut, so an evidence hiccup
  never reds the release train.
- **Wired (auto-activates):** **native GitHub artifact attestation** (`actions/attest-build-provenance`,
  Sigstore-backed, verifiable with `gh attestation verify`) over the SBOMs. It is gated
  `if: !repository.private` — a no-op while the repo is private (same plan gate as code-scanning) that
  switches on automatically the moment the repo goes public; until then the cosign-signed
  `<tag>.slsa.json` is the provenance that works.
- **Wired (advisory):** the deploy-time **`provenance.gate`** (ADR-0030 D4). The bundle is proven
  end-to-end on real releases (`Verify release evidence` workflow, cosign + manifest digests) and
  every deploy cosign-attests its image SBOM (`auto-deploy.yml`) — but deploy/admission do **not yet
  block**. State is `provenance.enforced: advisory` in `rules.yaml`; the four `enforce_criteria` there
  (full bundle coverage, fleet redeployed with image attestations, the kyverno SBOM-attestation rule
  staged Audit→Enforce, and a clean observation window) gate the flip to `enforce`. A blind flip would
  outage deploys of uncovered services (cf. the #770 image-signature Enforce revert).
- **Still `planned`** (do not claim them as done): embedding (vs referencing) coverage/test summaries;
  flipping `provenance.enforced` to `enforce` once the criteria hold; and the `oasdiff` api-contract
  gate (ADR-0048). `/release` reports which pieces CI can emit yet.

## Operational notes

- **Files:** [`release-please-config.json`](../../../release-please-config.json) (packages == the
  `version.txt` modules), [`.release-please-manifest.json`](../../../.release-please-manifest.json)
  (current release version per component), [`.github/workflows/release-please.yml`](../../../.github/workflows/release-please.yml).
- **Adding a service to the release train:** give it a `version.txt`, add it to both root files. The
  `released_unit_marker` in `rules.yaml` is `version.txt` — keep that true.
- **Baseline:** `last-release-sha` pins the point release-please treats as "already released", so the
  first Release PR only contains commits merged *after* Layer B landed.
- **CI on Release PRs:** they run the FULL required-check set — 29 checks on #3069, measured
  2026-08-01. The older claim here ("the action uses the default `GITHUB_TOKEN`; PRs it opens do not
  themselves re-trigger other workflows") stopped being true when the workflow moved to a GitHub App
  token for commit signing (#1276): an App token does re-trigger workflows. Since 2026-08-01 the
  service build is no longer among them — `services-ci.yml`'s PR path treats `version.txt` /
  `CHANGELOG.md` / the release manifest as inert, exactly as its push path already did, so a bump-only
  diff builds nothing. Per-service CI still runs on the feature PRs that feed the release, which is
  where a compile failure would actually come from.

## Skills

- [`/release <service>`](../../../.claude/skills/release/SKILL.md) — explain/verify the next release.
- [`/bump <service>`](../../../.claude/skills/bump/SKILL.md) — out-of-band version-source sync only.
- [`/ship-check`](../../../.claude/skills/ship-check/SKILL.md) — pre-merge preflight.
