# Contributing to OpenBank

Thank you for your interest in contributing! OpenBank is an open-source banking platform that aims to demonstrate banking-grade engineering practices in a community-friendly project. Please read this document before opening a pull request.

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold its terms. Report unacceptable behaviour by contacting the maintainer privately via the [@JiRaska GitHub profile](https://github.com/JiRaska) (not via public issues or GitHub Security Advisories — those are for security vulnerabilities only).

## TL;DR

1. **No direct commits to `main`.** Every change goes through a pull request.
2. **Sign your work** (`git commit -s`) — Developer Certificate of Origin (DCO).
3. **Signed commits** (GPG or SSH) are required.
4. **No secrets, ever.** Pre-commit hooks block them; CI re-checks.
5. **Every new behaviour needs a test.** No exceptions.
6. **Two reviewers** required for ledger / transaction / payment / auth code.

## How to Contribute

### Reporting bugs

- Open a GitHub issue using the **Bug report** template.
- Include reproduction steps, expected vs. actual behaviour, OpenBank version (commit hash), and environment details.
- **Security vulnerabilities go to GitHub Security Advisories, not public issues** — see [SECURITY.md](SECURITY.md).

### Proposing features

- Open a GitHub issue using the **Feature request** template.
- Larger features benefit from a draft Architecture Decision Record (ADR) in `docs/adr/`. Create one with **`docs/adr/new.sh "Your decision title"`** — it allocates a collision-free number (checking your local tree, `origin/main`, and every open PR) and scaffolds the YAML front-matter defined in [`docs/adr/SCHEMA.md`](docs/adr/SCHEMA.md). Do not hand-copy an existing ADR: the header is a validated block with closed enums, a closed tag vocabulary ([`tags.txt`](docs/adr/tags.txt)), and a required `summary` — the `adr-registry` CI gate rejects anything else, including the scaffold's own placeholders. Fill in `tags` and `summary`, then run `bash docs/adr/gen-index.sh && bash .github/scripts/check-adr-registry.sh` before pushing (`README.md`, `DIGEST.md` and `index.json` are derived — never hand-edit them). Open a PR promptly so the number is claimed.
- To find an existing decision, read [`docs/adr/DIGEST.md`](docs/adr/DIGEST.md) — the whole decision history at one line per ADR — rather than grepping the ADRs themselves.
- Discuss design before writing significant code — saves everyone's time.

### Submitting changes

1. Fork the repository and create a feature branch from `main`.
2. Make your changes following the guidelines below.
3. Ensure tests pass locally (`./gradlew test` for JVM, `npm test` for Admin UI).
4. Sign off and sign your commits (`git commit -s -S`).
5. Push your branch and open a pull request against `main`.
6. Address review feedback. Be patient — maintainers are volunteers.

## Developer Certificate of Origin (DCO)

OpenBank uses the [Developer Certificate of Origin](https://developercertificate.org/) instead of a CLA. The DCO is a developer's certification that they have the right to submit the patch.

Every commit must be signed off:

```bash
git commit -s -m "feat(ledger): add double-entry invariant assertion"
```

This adds a `Signed-off-by: Your Name <your.email@example.com>` trailer to your commit, certifying that:

> By making a contribution to this project, I certify that the contribution was created in whole or in part by me and I have the right to submit it under the Apache License 2.0; or the contribution is based upon previous work that, to the best of my knowledge, is covered under an appropriate open source license and I have the right under that license to submit that work under the same license.

PRs containing unsigned-off commits will be blocked by CI.

## Local development setup

### Prerequisites

| Tool | Required version | Notes |
|---|---|---|
| JDK | **25 (temurin-25)** | NOT JDK 26 — the Gradle toolchain is pinned to 25. JDK 26 breaks mockk/Objenesis. Install via [SDKMAN](https://sdkman.io/) or [Homebrew](https://brew.sh/) and set `JAVA_HOME` explicitly. |
| Gradle wrapper | bundled (`./gradlew`) | No global install needed. |
| Docker Desktop | 4.x+ | Required for Testcontainers integration tests. 16 GB RAM recommended for the full fleet; 24 GB+ if building multiple services in parallel. |
| Node.js | 22+ | Only needed for `openbank-admin-ui`. |

> **JDK version matters.** Mac default JDK may be JDK 26; the Gradle toolchain is pinned to 25.
> mockk/Objenesis fails with `ObjenesisException` on JDK 26. Symptom: tests that pass in CI fail
> locally with reflection errors. Fix: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` (macOS)
> or use SDKMAN: `sdk use java 25-tem`.

### Build a single service

```bash
./gradlew :openbank-<service>-service:build
# Example:
./gradlew :openbank-ledger-service:build
```

### Run all checks (same as CI gate)

```bash
./gradlew detekt ktlintCheck koverVerify build
```

This mirrors the exact checks the CI gates enforce (ADR-0029). Run this before opening a PR.

> **Maintainers only:** the `/ship-check` Claude Code skill runs the same gates interactively and
> reports what is still missing (version bump, changelog, openapi, tests, threat model). It is not
> available to external contributors — the `./gradlew` command above is the equivalent.

### Validate CDI wiring (important for Quarkus services)

```bash
./gradlew :openbank-<service>-service:quarkusBuild
```

`ktlintCheck` and unit tests do **not** validate Quarkus CDI / ArC wiring. `quarkusBuild` (without
Docker) is the earliest point where CDI failures surface. Add it to your pre-push routine when
modifying producers, interceptors, or `@ApplicationScoped` beans.

### Run admin-ui locally

```bash
cd openbank-admin-ui
npm install
npm run dev
# Open http://localhost:3000
```

### Local infrastructure stack (Docker Compose)

The full local infrastructure stack (Postgres, Kafka, Keycloak, OpenBao, OPA, Valkey, observability)
is in `openbank-infra/docker/docker-compose.yml`:

```bash
cd openbank-infra
cp .env.example .env   # adjust secrets for local dev
make up-infra          # start Postgres, Kafka, Keycloak, Vault, OPA, Valkey
make up-all            # build + start all application services
make health-all        # verify health endpoints
```

Alternatively, for **a single service** you usually do not need the full stack — Quarkus DevServices
starts the required containers automatically:

```bash
./gradlew :openbank-<service>-service:quarkusDev
```

### IDE setup

- **IntelliJ IDEA** — import the root `build.gradle.kts` as a Gradle project.
- Kotlin plugin 1.9+ (bundled in IntelliJ 2024+).
- [Quarkus plugin](https://plugins.jetbrains.com/plugin/13234-quarkus) — optional but recommended
  (live reload, application.yaml completion).

### Common local pitfalls

- **Two concurrent `./gradlew` instances stomp each other.** A second Gradle instance stops the
  first daemon and can corrupt `~/.gradle/caches`. If you run integration tests from the IDE and
  the CLI in parallel, isolate them:
  `GRADLE_USER_HOME=/tmp/gradle-isolated-$$ ./gradlew :openbank-foo-service:test`
- **Stale `build/quarkus-app/`** causes `ClassNotFoundException` at boot after interrupted builds.
  Delete `build/quarkus-app/` and `build/classes/` before re-building if the previous build was
  interrupted.
- **`fun foo() = runBlocking { }` drops tests silently.** JUnit 5 requires `void`-returning test
  methods. Always write `fun foo(): Unit = runBlocking { }` or use the Kotlin coroutine test runner.

## Workflow

```
issue → fork → branch → commit -s -S → push → PR → review → CI green → squash-merge → main
```

### Branch naming

- `feat/<scope>-<summary>` — new functionality
- `fix/<scope>-<summary>` — bug fix
- `chore/<scope>-<summary>` — non-functional change
- `docs/<scope>-<summary>` — docs only
- `refactor/<scope>-<summary>` — internal restructure
- `test/<scope>-<summary>` — test-only changes
- `security/<scope>-<summary>` — security fix (typically private until disclosed)

`<scope>` = service name without `openbank-` prefix (e.g. `ledger`, `psd2`, `sepa`).

### Commit messages (Conventional Commits)

```
<type>(<scope>): <short summary in imperative mood>

<body — what & why, not how>

<footer — refs, breaking-changes, sign-offs>
```

Types: `feat`, `fix`, `chore`, `docs`, `refactor`, `test`, `perf`, `security`, `build`, `ci`.

Example:

```
feat(ledger): add double-entry invariant assertion

Enforces Σ debits = Σ credits per journal entry at the domain layer.
Prevents inconsistent postings from being persisted.

Refs: #42
Signed-off-by: Jane Doe <jane@example.com>
```

### Signed commits

We require cryptographic signatures (GPG or SSH) in addition to DCO sign-off:

```bash
# SSH signing (recommended — simpler)
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
git config --global commit.gpgsign true

# Then commit with both DCO sign-off AND cryptographic signature
git commit -s -S -m "your message"
```

See [GitHub's documentation](https://docs.github.com/en/authentication/managing-commit-signature-verification) for setup details.

## Definition of Done

A pull request is **ready to merge** only when ALL apply:

- [ ] Code follows hexagonal architecture (domain has zero framework imports).
- [ ] Unit + integration tests cover the change. Coverage does not decrease.
- [ ] `./gradlew detekt ktlintCheck koverVerify build` passes locally (when applicable).
- [ ] No new lint warnings.
- [ ] No new dependencies without security and license review (see below).
- [ ] Public API change → OpenAPI spec updated.
- [ ] DB change → Flyway migration + rollback note.
- [ ] Event change → schema versioned with backward compatibility.
- [ ] Docs updated (README, ADR if architectural).
- [ ] Commits are signed off (DCO) and cryptographically signed.
- [ ] Two approvals if touching ledger / transaction / payment / auth code.

## Security Checklist (every PR)

- [ ] No hardcoded secrets, tokens, passwords, PII, or real customer data.
- [ ] No `@SuppressWarnings`, `as Any`, `@Suppress("...")` without justification in PR description.
- [ ] No new third-party dependency without:
  - License check (allowlist: Apache-2.0, MIT, BSD-2/3, EPL-2.0, MPL-2.0, ISC). GPL and AGPL would force copyleft on the Apache-2.0 tree — open an issue first.
  - CVE check (no Critical / High open vulnerabilities).
  - Reasonable source review (no obfuscated or suspicious patterns).

### Real names in tracked files

A real person's name can appear in a tracked file two different ways, and they look identical to a
reader — only one of them is a problem. Ask two questions before naming a real person anywhere in
this repository:

1. Is this row **sourced reference data** (drawn from a public register, with an external source
   identifier attached), or an **invented fixture**?
2. Does the classification carry an **implication about the person** beyond what the source itself
   asserts?

**Sourced reference data is fine, and expected.** A sanctions/PEP screening list seeded from a
public register (OFAC, EU, UN, or a national register) legitimately names real, public figures —
that is the correct content for that kind of seed data. Each such row must carry its source
identifier (e.g. a Wikidata id) so the classification is traceable to the source rather than
invented, and so the identifier's presence gives a reviewer (or a future check) a usable signal to
tell it apart from a fixture.

**An invented fixture must not use a real person's name**, especially not in a context — like an
adverse-media / negative-news test — where the classification implies something about that person
the source data does not assert. Use an obviously fictional name instead (`Adverse Subject Zero`,
`Jan Novák`), never a real living or recently deceased person, even a public figure.

A blanket "no real names" rule would be wrong — it would forbid legitimate sourced reference data.
The source identifier is the discriminator: sourced rows carry one, invented fixtures never should
need one because they never claim to be real.

## What NOT to do

- Don't bypass CI (`[skip ci]` is forbidden on `main`).
- Don't disable tests to make CI green. Fix the test or the code.
- Don't commit `.env` files, keys, dumps, or screenshots with real data.
- Don't push directly to `main`. Branch protection will block you.
- Don't squash someone else's commits without permission.
- Don't merge your own PR if it touches CODEOWNERS-protected paths.
- Don't open PRs for security vulnerabilities — use GitHub Security Advisories.

## Local Development

See the [Local development setup](#local-development-setup) section above for prerequisites,
build commands, Docker Compose stack, and common pitfalls.

## Architecture Decision Records (ADR)

Architectural changes require an ADR in `docs/adr/`. See [`docs/adr/0001-record-architecture-decisions.md`](docs/adr/0001-record-architecture-decisions.md) for the process.

## License of Contributions

By contributing to OpenBank, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE), the same license as the project. The DCO sign-off on each commit is your acknowledgement of this.

## Questions?

- **General questions:** open a GitHub Discussion (when enabled) or an issue with the `question` label.
- **Security issues:** [SECURITY.md](SECURITY.md).
- **Code of conduct concerns:** contact the maintainer privately via the [@JiRaska GitHub profile](https://github.com/JiRaska) (not via public issues or GitHub Security Advisories).

Thank you for helping make OpenBank better!
