# OpenBank — Project Governance

## Current status (as of 2026)

OpenBank is now public (Apache-2.0, launched June 2026) and in a **single-maintainer beta phase**.
All decisions and merge rights are held by the single maintainer. This document is
a living record of the interim governance model and the path to a distributed one.

## Roles

| Role | Who | Responsibilities |
|------|-----|-----------------|
| **Maintainer** | The single maintainer | Architecture, security review, release gating, merge rights |
| **Contributor** | Open to anyone | PRs, issues, discussions — see [CONTRIBUTING.md](CONTRIBUTING.md) |

## How the 2-approval rule is satisfied today

[ADR-0030](docs/adr/0030-supply-chain-security-and-ssdlc-hardening.md) and
[`openbank-libs/governance/rules.yaml`](openbank-libs/governance/rules.yaml) state a goal of **two
distinct reviewers** for every money-path change (the services listed under `money_path_services`).
**That goal is not met and is not enforced.** `rules.yaml` records it as
`enforcement_status: aspirational_not_enforced` (tracked in #2183): the repository has one account
with write access, and GitHub does not allow approving your own pull request, so requiring an approval
would only make `main` unmergeable without an administrator bypass.

**What is actually in force:**

1. **Required status checks** on the `main-protection` ruleset are the enforced control. A change
   cannot merge until they pass. Nothing blocks a merge on approvals today.
2. **Human review by a second person is a stated goal, not a control.** It happens only when a
   reviewer is available, and no merge depends on it. Do not read the two-approval number in
   `rules.yaml` as a gate.
3. The threat model (`docs/threat-models/<service>.md`) for money-path changes is required by
   ADR-0030 and checked by a diff gate; it is meant to be written before the PR is opened.
4. The money-path-only gates in `.github/gates/gates.yaml` and the runtime four-eyes gate on
   money-path verbs are further independent controls, separate from GitHub review.

This is explicitly a stop-gap tied to a capacity limit, not a policy decision. It expires when a
second account holds write access (see the revisit trigger in `rules.yaml`).

## Path to distributed governance

The target model (to be formalized in a future governance ADR):

1. **Second maintainer** — at least one additional person with merge rights and CODEOWNERS coverage
   over money-path code. Candidate criteria: >5 merged PRs to money-path services, passed a security
   design review with the current maintainer.
2. **CODEOWNERS expansion** — split `.github/CODEOWNERS` by bounded context, with at least two
   maintainers per money-path group.
3. **Formal committer track** — documented in this file: contribution → committer → maintainer path
   with clear criteria.
4. **Technical Steering Committee (TSC)** — for large architectural decisions, once the contributor
   base exceeds ~10 regular contributors.

## Continuity if the maintainer is incapacitated

The project is designed to survive the loss of its single maintainer with minimal interruption
(OpenSSF Best Practices `access_continuity`):

1. **Credential continuity.** A designated trusted contact holds emergency access to the
   maintainer's credential vault (a time-delayed grant via the vault provider's emergency-access
   mechanism). The vault contains everything needed to assume the Maintainer role: repository owner
   account access and recovery codes, and the release-signing key material. The identity of the
   contact and vault specifics are deliberately not published.
2. **No manual release machinery.** Releases are fully automated (release-please; see
   [Release process](#release-process)) — the successor only needs merge rights to cut releases,
   close issues, and accept changes. All CI/CD, infrastructure definitions, and governance rules
   live in this repository as code; there are no undocumented manual steps.
3. **Fork backstop.** Everything required to continue the project — source, documentation, CI
   definitions, infrastructure-as-code, governance rules — is public under Apache-2.0, so the
   community can continue the project from a fork even in the worst case.

## Decision-making

- **Architectural decisions** are recorded as ADRs in `docs/adr/` (see [README](docs/adr/README.md)).
  Anyone can propose an ADR via a PR; the maintainer approves.
- **Security issues** must be reported via GitHub's private Security Advisory flow, not as public
  issues. See [SECURITY.md](SECURITY.md) for the disclosure policy.
- **Feature requests and bugs** are tracked in GitHub Issues (see
  [CONTRIBUTING.md](CONTRIBUTING.md#how-to-contribute)).

## Release process

Releases are fully automated via release-please from Conventional Commits. The maintainer triggers
no manual release steps — merging a release-please PR is sufficient. See
[openbank-libs/governance/RELEASE.md](openbank-libs/governance/RELEASE.md).

## License and DCO

OpenBank is licensed under the [Apache License 2.0](LICENSE). All contributions require a
Developer Certificate of Origin sign-off (`git commit -s`). See [ADR-0123](docs/adr/0123-relicense-to-apache-2.0.md)
for the relicensing history and [ADR-0012](docs/adr/0012-mpl-license-and-dco.md) for the DCO policy.
