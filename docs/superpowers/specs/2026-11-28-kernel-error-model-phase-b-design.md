# ADR-0326 phase b: catalogue, ratchet and a compatibility-first pilot

## Approved scope

Implement the first usable slice of issue #11610 without starting phases c or d:

1. Derive the fleet error-code catalogue from every production Kotlin enum implementing
   `com.openbank.libs.domain.error.ErrorCode`.
2. Enforce wire format, fleet-wide code uniqueness, and append-only code/category/title/retryability
   semantics against a committed baseline. Existing intentional duplicate codes are recorded as
   explicit legacy owners; a new owner is rejected.
3. Generate the human-readable catalogue and one shared OpenAPI `ProblemDetail` component from the
   same scanner. Generated files are refreshed through `regen-derived.sh` and checked for drift.
4. Add a shrink-only baseline of service-local `ExceptionMapper` declarations. Named classes and
   anonymous implementations count; libs-owned mappers and PSD2's mandated Berlin Group mappers are
   excluded. A new occurrence and a stale baseline entry both fail.
5. Add an optional legacy `error` member to the typed error path. It carries the exact legacy value
   only where an existing `{error}` contract requires it; ordinary typed errors omit it.
6. Migrate only `ProductNotFoundException` and `DuplicateProductCodeException` in product-catalog.
   Their 404/409 statuses and legacy `error` messages stay unchanged, while the response gains the
   typed `ProblemDetail` members and stable catalog-owned codes. Delete their two local mappers.
7. Update product-catalog's OpenAPI contract and contract/equivalence tests. Classify the spec diff
   with the repository's API-contract tooling rather than assuming a version bump.

Explicitly excluded: the 412, 428 and catalog-validation mappers (their current wire shapes do not
fit the category/status table without a separate contract decision), generic JDK mapper demotion,
global `require`/`requireNotNull` replacement, money-path migration, and closure of #11610.

## Design choices and alternatives

The recommended approach is **gates first, then one narrow pilot**. A fleet-wide mapper sweep would
mix incompatible response contracts and several release units into an unreviewable change. A
catalogue-only PR would be safer but would not prove that the kernel model can preserve a real
legacy consumer contract. The selected sequence proves both halves independently.

The catalogue scanner is source-based and deterministic. It recognizes enum entries, explicit
`ErrorCategory` constructor arguments, titles, and retryability overrides. It rejects an enum shape
it cannot evaluate instead of silently omitting it. Codes are keyed by their wire spelling; the
baseline remains after an implementation owner disappears, because removing a published code is not
a valid migration. Duplicate legacy owners are listed separately from the append-only code lines.

The compatibility member is deliberately narrow: `DomainException` carries an optional
`legacyError`, `NotFound` and `Conflict` expose it to service subclasses, and
`DomainExceptionMapper` copies it to `ProblemDetail.error`. This avoids a general arbitrary-member
bag and cannot overwrite RFC 9457 or `ApiError` fields. Existing callers of every constructor retain
their defaults.

## Delivery boundaries

The work is split into stacked, independently reviewable changes:

- `feat(libs)`: catalogue scanner, baseline, generator, gates, generated reference/OpenAPI component,
  and the optional legacy-error compatibility path.
- `feat(product-catalog)`: the two-exception pilot, OpenAPI update, and service tests.

No `version.txt`, changelog, or application version is edited by hand. Each PR references #11610
with `Refs`, not `Closes`.

## Verification

- Each checker has known-positive and known-negative self-tests, including duplicate codes,
  category/title changes, removed codes, malformed codes, new mapper declarations, anonymous
  mapper declarations, allowed exclusions, and stale baselines.
- Generator output is reproducible and registered in the derived-artifact inventory.
- Libs domain/runtime unit tests prove the legacy member is omitted by default and preserved when
  requested, including JSON serialization and unchanged status/code/message behavior.
- Product-catalog equivalence tests drive `DomainExceptionMapper` and assert the legacy `error`
  value plus the added typed fields. The service suite, clean build, `quarkusBuild`, ktlint and
  detekt run after the interface change.
- Product-catalog's OpenAPI diff and version are checked with the repository contract gate; Pact
  replay remains authoritative for the legacy body.
- The full gate manifest runs with the required PR context where available. Duplicate YAML keys and
  generated-artifact freshness are checked explicitly.
