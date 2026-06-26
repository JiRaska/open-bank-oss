# Contributing to OpenBank

Thank you for your interest in contributing! OpenBank is an open-source banking platform that aims to demonstrate banking-grade engineering practices in a community-friendly project. Please read this document before opening a pull request.

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold its terms. Report unacceptable behaviour by opening a confidential issue via GitHub Security Advisories.

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
- Larger features benefit from a draft Architecture Decision Record (ADR) in `docs/adr/`. Use [`docs/adr/TEMPLATE.md`](docs/adr/TEMPLATE.md) as a starting point.
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

> By making a contribution to this project, I certify that the contribution was created in whole or in part by me and I have the right to submit it under the Mozilla Public License 2.0; or the contribution is based upon previous work that, to the best of my knowledge, is covered under an appropriate open source license and I have the right under that license to submit that work under the same license.

PRs containing unsigned-off commits will be blocked by CI.

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
  - License check (allowlist: Apache-2.0, MIT, BSD-2/3, EPL-2.0, MPL-2.0, ISC). GPL and AGPL are NOT compatible with MPL-2.0 in the same file — open an issue first.
  - CVE check (no Critical / High open vulnerabilities).
  - Reasonable source review (no obfuscated or suspicious patterns).

## What NOT to do

- Don't bypass CI (`[skip ci]` is forbidden on `main`).
- Don't disable tests to make CI green. Fix the test or the code.
- Don't commit `.env` files, keys, dumps, or screenshots with real data.
- Don't push directly to `main`. Branch protection will block you.
- Don't squash someone else's commits without permission.
- Don't merge your own PR if it touches CODEOWNERS-protected paths.
- Don't open PRs for security vulnerabilities — use GitHub Security Advisories.

## Local Development

See [README.md](README.md) for environment setup.

## Architecture Decision Records (ADR)

Architectural changes require an ADR in `docs/adr/`. See [`docs/adr/0001-record-architecture-decisions.md`](docs/adr/0001-record-architecture-decisions.md) for the process.

## License of Contributions

By contributing to OpenBank, you agree that your contributions will be licensed under the [Mozilla Public License 2.0](LICENSE), the same license as the project. The DCO sign-off on each commit is your acknowledgement of this.

## Questions?

- **General questions:** open a GitHub Discussion (when enabled) or an issue with the `question` label.
- **Security issues:** [SECURITY.md](SECURITY.md).
- **Code of conduct concerns:** confidential GitHub Security Advisory.

Thank you for helping make OpenBank better!
