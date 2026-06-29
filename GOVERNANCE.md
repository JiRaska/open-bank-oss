# OpenBank — Project Governance

## Current status (as of 2026)

OpenBank is in a pre-public, single-maintainer phase. All decisions and merge rights are held by
[@JiRaska](https://github.com/JiRaska). This document is a living record of the interim governance
model and the path to a distributed one.

## Roles

| Role | Who | Responsibilities |
|------|-----|-----------------|
| **Maintainer** | @JiRaska | Architecture, security review, release gating, merge rights |
| **Contributor** | Open to anyone | PRs, issues, discussions — see [CONTRIBUTING.md](CONTRIBUTING.md) |

## How the 2-approval rule is satisfied today

[ADR-0030](docs/adr/0030-money-path-threat-modelling.md) and
[`openbank-libs/governance/rules.yaml`](openbank-libs/governance/rules.yaml) require **two distinct
reviewers** for every money-path change (the services listed under `money_path_services`). With a
single full-time maintainer this cannot be met structurally by GitHub's branch-protection alone.

**Interim model:**

1. Money-path PRs are reviewed by the maintainer (@JiRaska) **and** by an external technical reviewer
   invited via the PR's `Reviewers` field. External reviewers are invited from a rotation of security
   and fintech engineers with domain expertise. Their approval is recorded on the PR before merge.
2. The threat model (`docs/threat-models/<service>.md`) for money-path changes is written before the
   PR is opened, not after — so the external reviewer has a concrete security surface to validate.
3. PRs are kept open for a minimum of 24 hours to allow async review, even when CI is green.

This is explicitly a stop-gap. It works while the contributor base is small and every PR is known to
the maintainer. It does not scale to a high-volume open-source project.

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

## Decision-making

- **Architectural decisions** are recorded as ADRs in `docs/adr/` (see [README](docs/adr/README.md)).
  Anyone can propose an ADR via a PR; the maintainer approves.
- **Security issues** must be reported via GitHub's private Security Advisory flow, not as public
  issues. See [SECURITY.md](SECURITY.md) for the disclosure policy.
- **Feature requests and bugs** are tracked in GitHub Issues (see
  [CONTRIBUTING.md](CONTRIBUTING.md#issues)).

## Release process

Releases are fully automated via release-please from Conventional Commits. The maintainer triggers
no manual release steps — merging a release-please PR is sufficient. See
[openbank-libs/governance/RELEASE.md](openbank-libs/governance/RELEASE.md).

## License and DCO

OpenBank is licensed under the [Apache License 2.0](LICENSE). All contributions require a
Developer Certificate of Origin sign-off (`git commit -s`). See [ADR-0123](docs/adr/0123-relicense-to-apache-2.0.md)
for the relicensing history and [ADR-0012](docs/adr/0012-mpl-license-and-dco.md) for the DCO policy.
