# Risk engine architecture

The service derives risk measures from a frozen, dated balance-sheet snapshot. It is not the ledger or a second system of record. The application layer coordinates snapshot creation, tie-out, curve selection and read-side projections; domain code owns instrument and cash-flow calculations; infrastructure adapters read upstream data and persist the snapshot manifest.

## Data path

1. A snapshot request fixes an as-of date and a recorded-time cutoff. The service reads the ledger trial balance, sub-ledger balances and contract-level loan data through their owners' APIs. Treasury money-market instruments enter the same canonical instrument model.
2. The input hash and source references identify the frozen run. PositionBuilder maps each source to signed positions and typed instruments.
3. Tie-out compares positions by GL account and currency with the ledger. A run that does not tie out is retained for investigation but cannot produce published measures; read endpoints return 409.
4. Cash-flow projectors expand supported instruments into dated principal and interest flows. Curves and scenarios then produce IRRBB and liquidity measures on read. Cash flows and measures are projections, not separate authoritative records.
5. The service stores runs, positions and operator-supplied curve sets in PostgreSQL. The migrations in `src/main/resources/db/migration` define the persisted shape.

The currently implemented HTTP surface is described by `src/main/resources/openapi.yaml`. ADR-0313 and ADR-0314 describe the broader target architecture; their partial-delivery status is not a claim that every planned feed or workflow is live.

## Trust boundaries

Operator reads and writes use distinct roles. A human operator can create runs and curve sets; the service does not write the ledger database. Input provenance and the tie-out result remain attached to the run so a consumer can distinguish a calculated measure from a missing or inconsistent input. Missing curve or policy facts produce explicit non-evaluable results where the contract specifies them, rather than a fabricated zero.
