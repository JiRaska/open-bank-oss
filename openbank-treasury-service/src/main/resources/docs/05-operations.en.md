# 05 — Operations

## camt.053 XML intake

Treasury parses untrusted camt.053 uploads through `SecureXml`. Its DOM builder rejects DOCTYPE declarations and external entities before constructing a document. A rejected or malformed upload must be corrected at its source; do not weaken parser settings to accept it. The service does not make an outbound fetch for entities named by the file.

## Mutation testing scope

`pitest` targets `com.openbank.treasury.domain.*`, `com.openbank.treasury.application.usecase.*`
and `com.openbank.treasury.nostro.*`. The nostro reconciliation suite (Mt940Parser, NostroMatcher,
NostroBreakBook) is plain JUnit with no Quarkus boot; excluding it from `targetTests` left every
nostro mutant at NO_COVERAGE by construction and masked the real score. Keep new pure-JUnit suites
inside the target set when they cover production classes in the targeted packages.

## Ledger accounting-class rollout (ADR-0341 D3)

Treasury can post by accounting class (the ledger resolves the GL account) or with explicit GL accounts.
`openbank.treasury.ledger.post-by-class` (env `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS`) chooses, and defaults to `false`, which keeps the explicit payload every ledger
understands.

Rollout order, always:

1. Deploy the ledger that carries migration `V32__accounting_class_gl_map.sql`, then confirm it serves
   `GET /api/v1/accounting-classes` (200).
2. Only then set `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS=true` for Treasury and restart it. The flag is read at startup.

Deploying Treasury before the ledger is safe while the flag is `false`.

Nostro reconciliation follows the same switch: with `false` the nostro accounts come from the built-in
shim chart, so an older ledger answering 404 on the class map cannot stop uploads or reconciliation; with
`true` the ledger map is read once per upload or reconcile run. A reversal always offsets the accounts
recorded on the deal's own journals (`deal_journals.resolved_accounts`), whatever the map says today.

**Rollback.** With the flag `true` the legs carry no account, so an older ledger (below `V32`) refuses
every line. There is no rollback by configuration alone: set `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS=false` and redeploy Treasury FIRST,
and only then roll the ledger back. Rolling the ledger back first fails every posting until Treasury is
redeployed.
