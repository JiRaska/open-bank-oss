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

Every accepted key, including a new key for identical bytes, is durably bound to the returned version. After later corrections it still replays that accepted version; different bytes under that key return 409. Migration V17 preserves existing statements' original keys and adds the binding table.


### Complete multi-account portfolio snapshots

Custodian statements and corrections are versioned independently per configured legal entity,
safekeeping account and statement date. The period-end read requires a current complete statement
for every configured account at exactly that date; a missing account is HTTP 409. A complete empty
account is valid evidence. Positions with the same ISIN are summed only when CFI, instrument class
and valuation currency agree; incompatible evidence is refused. The response lists each source
account's statement UUID, statement ID and version in `sourceStatements`, rather than attributing
an entity-wide portfolio to one account's statement. Accepted replay keys stay bound to their
original version, including after corrections.

V18 changes only uniqueness scope; existing statement and holding evidence is retained. Rollback
requires stopping writes and reconciling any multi-account history before restoring entity/date
uniqueness. Do not delete evidence or pick a single account to make old indexes fit.

After multi-account ingestion, prefer forward recovery; the old application cannot serve complete
multi-account snapshots. The following guard must succeed before considering restoration of the
old indexes. Reconciliation of evidence is an owner decision, not an automatic delete step.

```sql
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM portfolio_statements
        GROUP BY entity, statement_date
        HAVING count(DISTINCT safekeeping_account) > 1
    ) THEN
        RAISE EXCEPTION 'Rollback unavailable: retain and reconcile multi-account history; use forward recovery';
    END IF;
END $$;
```

### Quantity dimensions and legacy recovery

Every position retains the custodian's explicit `quantityBasis`: `UNIT`, `FACE_AMOUNT` or
`AMORTISED_VALUE`. Matching ISIN quantities are summed only when this dimension also matches;
unlike dimensions refuse the complete report with 409 instead of omitting an account. CFI
classification is not evidence of a quantity dimension.

V19 leaves existing holdings with an unknown (`NULL`) dimension. Those historical rows remain
immutable evidence, but a period-end report containing them is refused. Upload a verified source
correction for that account/date to create a new current version with the explicit dimension.
A byte-identical replay returns its original version and does not repair missing metadata.
Do not backfill dimensions from instrument class or CFI. Before rollback, stop writes and retain
the dimension evidence; an older binary cannot safely aggregate unlike dimensions, so keep
period-end reporting unavailable until forward recovery is complete.

### Company reporting identity

The company-instance deployment uses the synthetic portfolio entity `company`, matching the
existing `openbank.statutory-returns.company-id` used by tax-reporting for statutory returns and
the corporate register. The namespace `treasury-pension-co` is a transport/isolation identity,
not that reporting identifier. The company ledger is isolated by its deployment and database;
it has no entity column or entity-ID configuration (ADR-0337). Keep its company URL separate
from the bank ledger.

Before real ingestion, the owner must verify the reporting identity and custodian accounts
consistently across both services. Changing an identifier after ingestion does not migrate
immutable statements, register entries or stored returns: retain their evidence and reconcile
the history before a coordinated change. This configuration change neither renames existing
tax-reporting data nor provisions or deploys the company instance.
