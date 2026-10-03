<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Repository layout

The root is the project's front door: navigation, contributor policies, build entry
points, and component directories. Put detailed documentation in `docs/`, reusable
utilities in `scripts/`, CI machinery in `.github/`, and service-specific code and
configuration in the owning component.

| Location | Purpose |
|---|---|
| `README.md`, `ARCHITECTURE.md`, `DEPLOYMENT.md`, `RELEASE_NOTES.md` | Starting points for users, contributors, and operators |
| `CONTRIBUTING.md`, `SECURITY.md`, `GOVERNANCE.md`, community and licence files | Contribution, disclosure, ownership, and licensing policies |
| `AGENTS.md` → `CLAUDE.md` | Shared contributor and agent instructions |
| `openbank-*` | Applications, shared libraries, infrastructure, and contracts |
| `build-logic/`, `gradle/`, root Gradle files | Build conventions, wrapper, and dependency verification |
| `docs/` | Architecture, roadmap, decisions, runbooks, and evidence |
| `pacts/`, `fuzz/`, `perf/`, `evals/` | Contract fixtures, fuzzing, performance, and evaluation assets |
| `config/`, `scripts/` | Shared configuration and reusable development tools |
| `.github/`, `.clusterfuzzlite/`, `.devcontainer/`, `.security/`, `.semgrep/` | Automation, development environment, and security controls |
| `LICENSES/`, `REUSE.toml` | Licence texts and file attribution |

## What belongs elsewhere

- Temporary experiments and downloads: the system temporary directory or ignored
  root `tmp/`. Promote reusable tools into their owning directory before committing.
- Generated test reports, SBOMs, coverage, and logs: build outputs or CI artifacts.
- Local agent state and private instructions: ignored tool directories. Never publish
  private operational details or credentials in project guidance.
- Component Docker recipes: the component or the shared build pipeline; avoid a
  second root recipe that silently diverges from the canonical one.

Ignored caches are local working data, not public repository content. Do not delete
working checkouts, local instructions, or caches as part of a blanket cleanup.

## Enforced root policy

[`repository_root_layout`](../openbank-libs/governance/rules.yaml) declares permitted
root files and non-Gradle directories. New Gradle modules are recognised from a
tracked `openbank-*/build.gradle.kts`, matching `settings.gradle.kts` discovery.
Other new root entries need a reviewed policy change. Obsolete declarations fail
as well, so deleting a root entry also removes its permission.

After staging an explicit file list, run:

```bash
python3 .github/scripts/check-repository-root.py --self-test
python3 .github/scripts/check-repository-root.py
```

The check reads the **Git index**, including its policy and module markers. It
ignores unstaged local files and catches ignored files staged with `git add -f`.
It runs through pre-commit and the CI gate manifest. It controls root placement;
it does not replace secret scanning, licence checks, or review of component content.

## Keeping entry documentation current

Read the current version catalogue, package manifests, workflows, and ADR registry
when updating the README, deployment guide, or roadmap. Link to those sources
instead of maintaining duplicate component/version inventories. Milestone status
must cite evidence and distinguish implemented code from a measured deployment.
A checked-in manifest alone is not proof of runtime health or acceptance.

When a change alters setup commands, build inputs, deployment flow, or a roadmap
acceptance criterion, update the affected entry document in the same PR. Use the
roadmap's dated source review as the next review baseline. Historical release notes
and dated audit reports remain snapshots; do not silently rewrite them as live status.
