<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Root and entry-documentation audit — 2026-10-03

Baseline: `b46cd4670285` on `main`. Scope: tracked root layout, local scratch classification,
README, architecture/deployment entry points (including the evaluator guide), contribution/setup guidance, release navigation,
and the M1–M7 roadmap. This is a dated source audit, not a fleet test, vulnerability audit,
production-readiness certification or live deployment report.

## Findings and disposition

| Finding | Evidence at baseline | Change |
|---|---|---|
| No tracked temporary-root dump found | Git index inventory; no tracked files matched current ignore rules | Retain component directories and conventional tool configuration |
| Empty local scratch directory | Empty root `tmp/fc/`; no tracked content or source reference | Remove empty directories only; ignore root `tmp/` and `temp/` |
| No explicit root-placement contract | `.gitignore` filters local candidates but cannot reject `git add -f` | Declare exact root entries in governance and check the Git index in CI/pre-commit |
| Obsolete scanner recipe | `Dockerfile.scanner` hardcodes a snapshot runner JAR; its only tracked reference is the Dockerfile checker | Remove the unused root recipe and its checker inventory entry; retain component/shared recipes |
| Competing architecture entry points | Root guide and `docs/ARCHITECTURE.md` overlap but contain distinct detail | Keep a short root hub; preserve contributor detail under `docs/architecture-contributor-guide.md` |
| Stale release inventory | 43 listed changelogs versus 68 release configuration packages | Link the canonical registry and component changelogs instead of duplicating the list |
| Stale versions and component counts | README pins older backend/local infrastructure versions and conflates components with services | Link version/build manifests; separate applications, libraries and deployment inventory |
| Conflicting roadmap status | Overview says M1 complete; detailed July snapshot says ~75%; public launch described as future | Replace both with a dated evidence review and explicit acceptance criteria |
| Incorrect remaining gaps | Net-settlement posting exists; fraud rule engine exists; DR workflow has a quarterly schedule | Correct the gaps and state the narrower verification boundary |
| Invalid local setup path/command | Contributor guide names the wrong Compose path; Makefile `up-infra` uses `vault` while Compose defines `openbao` | Point to actual Compose configuration and document direct commands |
| Unverified deployment promises | Entry docs claim the full fleet is live, conflate desired state and observed health | Describe checked-in capabilities and point to runtime evidence for operational acceptance |
| Unsafe/generalised operator guidance | Public deployment guide includes recovery specifics and blanket Flyway repair advice | Remove those specifics; require authorised, service-specific recovery procedures |
| Fragile sandbox examples | Password-grant assumptions, copied write payloads, implied payment/transaction-ID equivalence | Use identity-scoped, read-first guidance and current API contracts |

## What stays deliberately

The flat `openbank-*` module layout is used by Gradle discovery, release configuration,
CI path selection and deployment scripts. Moving it under `services/` would be a separate,
large migration, not cosmetic cleanup. Community/licence documents and tool configuration
keep their conventional discovery locations.

Ignored caches and private working directories are local data. They are not public Git
content and were not deleted wholesale. Existing unrelated work was preserved. This pass
makes no claim about secret-free Git history or the safety of every ignored file.

## Prevention

- `repository_root_layout` is the authoritative root contract. Exact entry names require
  review; Gradle modules follow the existing tracked build-file marker.
- The checker reads index paths, policy and module markers together. Force-added ignored
  output fails; an unstaged policy edit cannot mask the next commit. Stale permissions fail.
- The same checker is registered in pre-commit and the required CI gate suite, with a
  subject floor, execution budget and negative-path self-tests.
- Root-only ignore patterns avoid hiding similarly named legitimate nested source paths.
- Entry documentation links changing inventories to their sources. The contribution guide
  requires documentation changes alongside changes to setup or acceptance criteria.

These controls prevent undeclared root additions; they cannot prove prose is current or
that a documented capability is running. Future acceptance claims still need scoped evidence.

## Remaining boundaries

The legacy Makefile helpers themselves are unchanged; their service-name and health-check
mismatch remains a follow-up outside this root/documentation change. The README uses direct
Compose commands instead. Full Docker startup and the fleet Gradle build were not executed.

ADR delivery metadata can lag implementation: outbox v2 shared code exists while ADR-0327
still says planned. This audit records that discrepancy; it does not relabel a design as
fully delivered. Historical strategy papers and dated operational evidence remain historical;
only the entry documentation and current acceptance plan were refreshed.

The root contributor/agent guide is still large. Splitting its cross-cutting rules and
path-scoped lessons needs a separate loading/coverage audit to avoid losing instructions.

## Verification of this change

- Eight affected gate checks passed through `run-gates.py`: root layout, runtime Dockerfiles,
  gate registration, observability declarations, lifecycle metadata, subject floor, self-test
  declarations, and duplicate YAML keys. The root guard exercised 13 real Git-index/CLI cases.
- Licence consistency and gate invocation reachability passed with their self-tests.
- Gate preservation against the base passed; the derived OPA rules data remains in sync.
- Changed Python files passed the CI Ruff rule set; staged diff whitespace and secret scans passed.
- Local Markdown targets were checked; five diagrams parsed with the UI's installed Mermaid 12.0.0.
- `docker compose --env-file .env.example config --quiet` and service enumeration passed.
  No containers were started and no full Gradle fleet build was run.
- The existing stale-comment gate's online check found an unrelated baseline finding:
  `RemediationProposalAdapterTest.kt` references the archived `JiRaska/open-bank` repository.
  That file is unchanged by this work. This remains a CI issue, not a successful verification.

Full CI and required human review remain necessary before merge. The new governance/CI files
are protected by the existing agent PR guard; this change does not modify or bypass it.
