# OpenBank Treasury Service API

The bank's own money-market deals (ADR-0315).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.


## Custodian portfolio statements

Upload a complete custodian `semt.002` XML statement with `POST /api/v1/treasury/portfolio/statements` and a nonblank `Idempotency-Key` of at most 128 characters. Upload requires the treasury approver role and the `treasury.portfolio.upload` authorization action; it records holdings and does not post accounting entries.

Configure `openbank.treasury.portfolio.entity`, `safekeeping-accounts`, and `cfi-classes` for the owning legal entity and permitted custody accounts. Instrument classes come from declared CFI prefixes; the longest matching prefix wins. Missing or unmapped CFI, an unconfigured custody account, or valuations in multiple currencies reject the complete upload. The XML reader also rejects incomplete pages, non-complete updates, duplicate or missing ISINs, and DOCTYPE declarations. Its vendored XSD is a working subset, not a certification of the full ISO message standard.

`GET /api/v1/treasury/portfolio/period-end?date=YYYY-MM-DD` reads the current statement at exactly that date, with entity, statement/version identifiers, currency, and positions. Quantity and valuation are decimal strings. An absent statement returns 409 `PORTFOLIO_SNAPSHOT_MISSING`; it does not become an empty portfolio. A recorded empty statement returns an empty positions array and the implementation's CZK currency default. The version-history endpoint `/statements?date=YYYY-MM-DD` can return an empty list when there are no versions.

Reusing a stored upload's key with the same bytes returns that stored version; different bytes under that key return 409. Uploading the current statement's bytes under another key returns its current version. Different bytes for the same entity/date create a new version and supersede the previous one, preserving its identifiers, SHA-256, uploader and supersession trail. Read the history to distinguish a correction from the original snapshot.

A deployment with no configured portfolio entity rejects uploads and has no period-end snapshot. Keep pension-company and bank treasury books separate; configure the entity and custody account allowlist before using this source for reporting. A stored portfolio alone does not prove reporting assembly, reconciliation or statutory submission.

Every accepted key, including a new key for identical bytes, is durably bound to the returned version. After later corrections it still replays that accepted version; different bytes under that key return 409. Migration V16 preserves existing statements' original keys and adds the binding table.
