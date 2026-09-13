---
date: 2026-05-31
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [api-contract, release-versioning, governance]
summary: "The API contract version splits from the service release version into three independent axes, with response headers, the URL major and a CI OpenAPI diff classifier enforcing that the contract moves only when the contract changes."
---

# 48. Decouple the API contract version from the service release version: three independent version axes

**Delivery note (updated 2026-07-06):**
- **D3** — ✅ Shipped: `ApiVersionResponseFilter` emits `X-API-Version: v{N}` (contract major) and
  `X-Service-Version: {semver}` (release axis). `ServiceInfoResource` body + headers both correct.
- **D5** — ✅ Shipped (advisory): `.github/scripts/check-api-contract.py` runs in the
  `Validate manifests` job on every PR — classifies breaking/additive/editorial from the OpenAPI
  diff (`oasdiff` 1.22.0, pinned + checksum-verified), validates the `info.version` bump against
  the classification, and asserts the D2 API invariant
  (`major(info.version) == openbank.api.version == newest URL major`). Findings are `::warning`
  annotations until the rules.yaml `api_change` gate flips to enforced
  (`target_enforce_date: 2026-08-15`, ADR-0144) — the flip is a one-line `--enforce` in ci.yml.
- **D6** — ✅ Shipped: `ApiVersionResponseFilter.isDeprecatedPath()` reads
  `openbank.api.deprecated-paths` (optional list, empty by default). `Sunset` reads
  `openbank.api.sunset-date`. Link header derives successor URL from current `openbank.api.version`.
  Hardcoded `false` and stale 2025-12-31 sunset removed. Configure in gitops when `/v{N+1}` ships.

## Context

ADR-0029 D2 set out to end "three version numbers per service, none of them true": each service
hardcoded `version = "0.1.0-SNAPSHOT"`, each `openapi.yaml` independently declared `info.version`, and
`ServiceInfoResource` reported the `0.0.0` default. The fix it chose was to **collapse the three into
one** — `version.txt == quarkus.application.version == openapi.yaml:info.version == git tag`
(now codified in `rules.yaml: versioning.single_source_invariant`, lines 57–62).

Collapsing *three accidental copies of the same intent* into one source was correct. But the invariant
went one step too far: it folded together **two semantically distinct version axes** that good
microservice practice keeps separate.

- **Service release version** — the version of the *deployable artifact*. It moves on **every** behaviour
  change: `feat` (minor), `fix`/`perf` (patch). It is what tags the container image, the git release, the
  SBOM and the signed evidence bundle, and what `/api/v1/info` reports as `version`.
- **API contract version** — the version of the *public REST contract*. It must move **only when the
  contract changes**. Its MAJOR is the compatibility boundary and belongs in the URL (`/api/v{N}`); a
  MINOR is an additive, backward-compatible change. It changes on a completely different cadence from the
  release version.

Yoking the API contract version to the release version produces three concrete defects, all visible in
the code today:

1. **Every internal fix rewrites the "API version".** A `fix` that never touches the REST surface still
   patch-bumps `version.txt`, and the invariant forces `openapi.yaml:info.version` to follow. A consumer
   reading `info.version` cannot distinguish "the API contract changed" from "an internal bug was fixed".
   The number that is supposed to signal contract compatibility is dominated by noise from internal
   changes.

2. **The URL major desynchronises from `info.version`.** All 22 domain resources hardcode
   `@Path("/api/v1/...")` (e.g. `openbank-account-service/.../AccountResource.kt:27`,
   `openbank-ledger-service/.../LedgerResource.kt:22`). The URL says major `1` forever, while the
   invariant drives `info.version` to `2.x`, `3.x`, … off the *release* cadence. A service at release
   `3.4.1` then serves `/api/v1` with `info.version: 3.4.1` — the URL claims major 1, the document claims
   major 3. They cannot both be the API's major.

3. **The version header is mislabelled and self-inconsistent.**
   `ApiVersionResponseFilter.kt:25` emits `X-API-Version = serviceVersion` — a header named for the API
   version actually carries the *build* version. `ServiceInfoResource` already gets this right in its
   **body** (`version = serviceVersion` *and* a separate `apiVersion = "v$apiVersion"` sourced from the
   `openbank.api.version` config, default `"1"`), but then contradicts itself on
   `ServiceInfoResource.kt:46` by setting the response header `X-API-Version` back to `serviceVersion`.
   The correct axis (`openbank.api.version`) already exists in the codebase — it is simply unused (only
   the default `"1"` is ever in play) and not wired to the header.

Standard practice across mature microservice platforms keeps these axes independent: the artifact has its
own SemVer; the API has its own SemVer whose MAJOR lives in the URL; the wire headers expose the contract
version, with the build version carried separately (or only in `/info`). Event/message schemas form a
third, again-independent axis (ADR-0006: Apicurio backward-compatible subjects, `-vN` topic suffix on a
break).

This ADR refines — it does not reverse — ADR-0029. ADR-0029's goal (no untrue versions, one source per
fact) stands. We keep one source for the release version and add one source for the contract version,
rather than pretending one number serves both.

## Decision

We will model versioning as **three explicit, independent axes**, and remove the API contract version
from the *release* single-source invariant.

### D1 — Three version axes

| Axis | Semantics | Single source of truth | Surfaced at | Bump trigger |
|---|---|---|---|---|
| **Release / artifact SemVer** | the deployable build of a service | `version.txt` → `quarkus.application.version` | `/api/v1/info` `version`; `X-Service-Version` header; git tag; image tag; SBOM; evidence bundle | Conventional Commit type (`feat`→minor, `fix`/`perf`→patch, `BREAKING CHANGE`→major) — unchanged from ADR-0029 |
| **API contract SemVer** | the public REST contract | `openapi.yaml:info.version` | URL `/api/v{MAJOR}`; `X-API-Version` header; `/info` `apiVersion` | classified from the OpenAPI diff: breaking→MAJOR, additive→MINOR, editorial→PATCH |
| **Event schema version** | Kafka message schemas | **Resolved by ADR-0260 (2026-08-16):** the committed `openbank-contracts/<service>/schema/<event>.schema.json` file — the Apicurio-assigned registry version is a *derived* artifact CI applies from it, not the source (same relationship `openapi.yaml` has to the API axis above) | topic name (`-vN` on a break); registry compatibility check (BACKWARD_TRANSITIVE fleet floor, FULL_TRANSITIVE on money-path, ADR-0260 D1) | breaking schema change (ADR-0006/ADR-0260) — restated here for completeness |

The three move on independent cadences. A service may sit at release `3.4.1` while serving API `v1`
(contract `1.2.0`) and producing `account.created` schema `v1`. That is the correct, expected state — not
drift.

### D2 — Amend ADR-0029 D2: split the single-source invariant

`rules.yaml: versioning.single_source_invariant` is split into two invariants:

- **Release invariant (unchanged in spirit, narrowed in scope):**
  `version.txt == quarkus.application.version == git release tag == image tag`.
  This remains the audit anchor for the evidence chain (ADR-0029 D5).
- **API invariant (new):**
  `major(openapi.yaml:info.version) == openbank.api.version == the URL "/v{N}" segment`.
  `openapi.yaml:info.version` is **removed** from the release invariant. Its MAJOR is bound to the URL and
  the runtime `openbank.api.version` config instead, so the document, the path and the header always agree
  on the API major.

### D3 — Fix the runtime header semantics (`openbank-libs`)

- `ApiVersionResponseFilter`: inject `openbank.api.version` (the contract major, already the config key
  `ServiceInfoResource` uses) and set `X-API-Version = "v{major}"` — the API contract major a client
  negotiates against. Add `X-Service-Version = {quarkus.application.version}` to carry the build/release
  version that `X-API-Version` was previously (wrongly) holding. `X-Service-Name` is unchanged.
- `ServiceInfoResource`: change the response header on line 46 from `serviceVersion` to the same
  `"v{apiVersion}"`, and add `X-Service-Version`, so the headers match the body the resource already
  returns. No body change — the body was already correct.

Net effect: `X-API-Version` becomes the *contract* axis everywhere, `X-Service-Version` is the *release*
axis everywhere, and the two are never again conflated.

### D4 — URL carries only the API major; v2 runs side-by-side

The `/api/v{N}` prefix encodes only the contract MAJOR. Additive (MINOR) and editorial (PATCH) changes
stay within the same `/v{N}` and must remain backward-compatible. A breaking change ships a **new**
`/api/v{N+1}` surface running **alongside** the old one; the old paths are marked deprecated (D6) with a
sunset window, never silently broken. Centralising the literal prefix (today repeated in 22 resources)
behind a single `@ApplicationPath("/api/v" + major)` or build-time constant is desirable but cosmetic and
out of scope here; the binding rule is "URL major == `info.version` major == config", however the literal
is expressed.

### D5 — Split the bump policy in `rules.yaml`

`change_requirements.api_change` currently requires `"semver bump >= minor"` without saying *which* axis,
which is exactly the conflation this ADR removes. It is split:

- **Release bump** — driven by commit type, as today (D1, unchanged).
- **API contract bump** — classified from the OpenAPI diff in CI (`oasdiff` or equivalent):
  - *breaking* (removed/renamed field, narrowed type, removed endpoint, new required input) ⇒ new URL
    MAJOR + deprecation of the predecessor (D6);
  - *additive* (new optional field, new endpoint) ⇒ `info.version` MINOR within the same `/v{N}`;
  - *editorial* (docs, examples) ⇒ `info.version` PATCH.

The `api-contract` gate (ADR-0029 D4) additionally asserts the D2 API invariant
(`major(info.version) == openbank.api.version == URL major`).

### D6 — Wire the deprecation plumbing to the API axis

`ApiVersionResponseFilter.isDeprecatedPath()` is hardcoded `false` (line 40). Drive it from
`rules.yaml: api_deprecation.deprecated_paths` (already a placeholder list) with the existing
`min_sunset_window_days: 180`. Deprecation operates on **API paths/majors**, not on release versions — a
`/v1` endpoint is deprecated when `/v2` ships, independent of how many times the service has been
released.

## Alternatives considered

- **Keep ADR-0029's "collapse three into one" (status quo).** Pros: one number to track; trivial
  `release-please` config. Cons: conflates two axes — consumers cannot tell a breaking API change from an
  internal fix; the URL major permanently desynchronises from `info.version`; the version header stays
  mislabelled. Rejected — this is the defect the ADR exists to fix.
- **URL-only versioning (drop `info.version` SemVer, keep only `/vN`).** Pros: simplest possible mental
  model. Cons: loses the additive-vs-editorial signal a MINOR/PATCH conveys; OpenAPI tooling, SDK
  generators and `oasdiff` all key off `info.version`. Rejected — the contract still wants a full SemVer,
  with only its MAJOR promoted to the URL.
- **Header / media-type content negotiation (`Accept: application/vnd.openbank.v2+json`) instead of a URL
  major.** Pros: keeps URLs stable across majors; RESTful purist's choice. Cons: heavier for clients,
  caches and proxies; harder to curl/observe; the platform already standardised on `/api/v1` in 22
  resources. Rejected for now — revisit only if a concrete consumer needs it; the two models are not
  mutually exclusive later.
- **One global API version across all services.** Cons: couples independent services exactly as a global
  *release* version would (ADR-0029 rejected that for the same reason) — a contract change in one service
  would imply a version move in all. Rejected — per-service, per-contract.

## Consequences

**Positive**
- `info.version` / `X-API-Version` become a *true* contract-compatibility signal: a consumer can tell a
  breaking change (new URL major) from an additive one (minor) from an internal fix (no API move at all).
- The release version is free to move on every `fix`/`feat` without implying an API change — the audit
  and evidence chain (ADR-0029 D5) keeps its anchor, and the API surface stays stable.
- URL major, `info.version` major and the runtime header always agree, enforced by a CI invariant.
- TPP/partner consumers (PSD2) get a stable, explicitly-deprecated contract with a guaranteed 180-day
  sunset, decoupled from internal release churn.

**Negative**
- Two version numbers per service to reason about instead of one (release + contract). Mitigated by clear
  header names (`X-Service-Version` vs `X-API-Version`) and the `/info` body exposing both.
- CI needs an OpenAPI-diff classifier (`oasdiff`) to drive the contract-bump gate; until it exists the
  `api-contract` gate stays advisory (consistent with ADR-0029's "enforce last" phasing).
- Renaming the *meaning* of `X-API-Version` is itself a wire change. Real-world impact is low because the
  header currently emits the `0.0.0` default rather than a value any consumer relies on, but it is noted
  as a contract change and shipped with the libs minor bump.

**Neutral**
- No change to the per-service release cadence, `release-please` components mode, or the signing/provenance
  flow — those operate purely on the release axis, which this ADR leaves intact.
- The event-schema axis (ADR-0006) is restated, not changed.

## Compliance impact

- PCI DSS: not applicable (no cardholder-data path change).
- DORA:    Art. 8–10 (change traceability) — unaffected; the release axis remains the versioned, signed
  audit anchor. Separating the API axis improves the precision of "what changed" records.
- GDPR:    not applicable (no personal-data path change).
- PSD2:    RTS on a stable TPP interface — **directly improved**: real API-contract versioning with
  enforced deprecation/sunset headers, no longer perturbed by internal release version churn.
- CNB:     improved change traceability — API-surface changes are now distinguishable from internal
  changes in the audit record.

## References

- ADR-0005 — OpenAPI design-first (API contract is the source for the contract axis).
- ADR-0006 — AsyncAPI for Kafka (the event-schema axis, third version axis here).
- ADR-0260 — Event schema format and compatibility: JSON Schema over Avro (resolves the D1
  event-schema row above; amends ADR-0006).
- ADR-0029 — Versioning, release and governance as code (this ADR amends its D2 single-source invariant
  and D5 audit chain framing; D1/D3/D4/D6 of 0029 are unchanged).
- ADR-0014 — `openbank-libs` as service-infrastructure layer (home of the runtime plumbing changed here).
- `openbank-libs/governance/rules.yaml` — `versioning.release_invariant` + `versioning.api_invariant`
  (the split from the former `single_source_invariant`, D2), `change_requirements.api_change` (split by
  D5), `api_deprecation` (wired by D6).
- `openbank-libs/.../web/ApiVersionResponseFilter.kt`, `ServiceInfoResource.kt` — the header semantics
  fixed by D3.
