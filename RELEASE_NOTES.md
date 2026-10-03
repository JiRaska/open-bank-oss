# Release notes

OpenBank uses [release-please](https://github.com/googleapis/release-please) to generate release notes from Conventional Commits. Component registration and release behavior are defined by [`release-please-config.json`](release-please-config.json) and [`.release-please-manifest.json`](.release-please-manifest.json). A released component keeps its generated history in `<component>/CHANGELOG.md`.

Browse published versions on the canonical [GitHub Releases page](https://github.com/JiRaska/open-bank-oss/releases), or discover component changelogs in the repository by locating directories that contain `CHANGELOG.md`. See [`openbank-libs/governance/RELEASE.md`](openbank-libs/governance/RELEASE.md) for release policy and the relationship between release and API-contract versions (ADR-0048).

Security-relevant fixes are called out under a `Security` heading in the relevant component changelog.
