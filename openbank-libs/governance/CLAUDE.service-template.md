<!--
  Per-service CLAUDE.md TEMPLATE. Copy to <service>/CLAUDE.md and fill the <PLACEHOLDERS>.
  Keep it SHORT — it is read every session on top of the root CLAUDE.md, which already covers
  the global non-negotiables. Only put here what is TRUE OF THIS SERVICE and not derivable at a
  glance. Do not restate global rules; link to ../CLAUDE.md and ../openbank-libs/governance/rules.yaml.
-->
# <SERVICE-NAME> (scope: `<scope>`)

<One sentence: what business capability this service owns. e.g. "Double-entry ledger; owns journal
entries and the Σ debits = Σ credits invariant.">

- **Bounded context / aggregates:** <e.g. JournalEntry, Account posting>
- **Money-path:** <yes/no> — if yes: 2 approvals + `docs/threat-models/<service>.md` required.
- **Local HTTP port:** <e.g. 8100>   **Management port:** <e.g. 8085>
- **Datastore:** <PostgreSQL schema name / Redis / none>   **Flyway:** `src/main/resources/db/migration`
- **Events:** produces `<topics>`, consumes `<topics>` (Avro schemas under `<path>`)
- **Key ports (hexagonal):** `application/port/in`, `application/port/out` — real adapters are
  build-time-gated `@Alternative @Priority`; `@Default` bindings are offline no-ops.

## Local run / test
```
./gradlew :<service>:build
./gradlew :<service>:quarkusDev      # if applicable
```

## Service-specific gotchas
- <anything surprising: a non-obvious invariant, a known drift, an ordering constraint with another
  service, a column that must stay NOT NULL, etc. This is the highest-value part of this file.>

> Global workflow, commit format, versioning and the ship-checklist: see [`../CLAUDE.md`](../CLAUDE.md)
> and [`../openbank-libs/governance/rules.yaml`](../openbank-libs/governance/rules.yaml).
