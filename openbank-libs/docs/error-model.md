# Kernel-owned error model

ADR-0326 gives services one typed exception hierarchy and one shared mapper for
client-visible failures. A service throws a `DomainException` subclass with a
catalogued `ErrorCode`; it does not add a service-local JAX-RS mapper to obtain a
different response envelope.

## Stable error-code catalogue

A service-owned code is a closed enum implementing `ErrorCode`. Its enum name is
its wire code, its category determines the HTTP status, and its title is stable,
client-safe prose. Published code metadata is append-only: renaming or removing a
code, changing its category or title, or changing retryability requires an
explicit contract decision rather than a baseline refresh.

The catalogue checker scans production Kotlin sources and compares them with
`.github/scripts/error-code-catalogue-baseline.tsv`. Existing duplicate wire-code
owners are individually recorded in
`.github/scripts/error-code-duplicate-owners.tsv`; a new duplicate is not made
acceptable by adding it to that legacy inventory.

After adding a code, regenerate the derived reference and shared OpenAPI schema:

```bash
python3 .github/scripts/gen-error-code-catalogue.py
python3 .github/scripts/check-error-code-catalogue.py --root .
python3 .github/scripts/gen-error-code-catalogue.py --check
```

The generated files are `docs/reference/error-codes.md` and
`openbank-libs/governance/error-model.openapi.yaml`. Edit the enum and baseline,
not the generated documents.

## Typed responses and legacy compatibility

`DomainExceptionMapper` renders a consistent typed exception as `ProblemDetail`.
The response retains the existing `ApiError` members while adding the RFC 9457
representation. `clientMessage` is safe for the caller; `internalDetail` remains
in logs. An exception whose code and category disagree is rendered as an internal
error instead of exposing either inconsistent value.

Phase b adds an optional `legacyError` value to `DomainException`, `NotFound` and
`Conflict`. When a migrated legacy endpoint previously returned `{"error": …}`,
the exception passes that exact client-safe value as `legacyError`. The mapper
then emits the optional `error` member alongside the typed body. Ordinary typed
exceptions leave it null and Jackson omits it. This compatibility path is not a
reason to preserve a service-local mapper, and the internal-error misuse path
never copies the legacy value.

## Service-local mapper ratchet

The `service-local-exceptionmapper-ratchet` gate enumerates named and anonymous
service-local `ExceptionMapper` declarations. New declarations fail. Removing a
legacy mapper requires deleting its exact baseline row in the same change; a
stale row also fails, so the inventory cannot drift silently.

A migration must preserve the established status and legacy body where clients
depend on them, extend the service OpenAPI contract with the actual response
members, and replay the affected contract tests. Phase b does not authorize a
fleet-wide replacement of `require`/`requireNotNull`, money-path migration, or
the later generic-mapper changes in ADR-0326 phases c and d.
