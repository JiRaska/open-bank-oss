# 05 — Operations

## camt.053 XML intake

Treasury parses untrusted camt.053 uploads through `SecureXml`. Its DOM builder rejects DOCTYPE declarations and external entities before constructing a document. A rejected or malformed upload must be corrected at its source; do not weaken parser settings to accept it. The service does not make an outbound fetch for entities named by the file.

## First checks for a stalled deal

Read the deal and its last transition through the authenticated treasury API before retrying an action. A pending approval still requires a different human approver; a rejected product mandate cannot be cleared with the senior counterparty override. For a booked deal whose financial effect is missing, compare the treasury transition, its outbox delivery and the ledger's idempotent journal result. Replaying the same transition without identifying which boundary failed can obscure a ledger or event-delivery fault.

For reconciliation, inspect the statement's reconciliation result and outstanding breaks. A break represents a difference between expected and observed settlement; do not mark it settled from a successful XML upload alone. The repository runbook `docs/runbooks/svc-treasury.md` covers service health and recovery; deployment configuration remains in GitOps.

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
`true` the ledger map is read once per upload or reconcile run. A reversal offsets, per GL account,
exactly what the deal's own journals posted there (`deal_journals.resolved_accounts`), whatever the map
says today: accruals that landed on two accounts across a map re-point are unwound as two lines.

**Reversal refused with 409 `REVERSAL_ACCOUNTS_UNKNOWN`.** A journal being reversed has no recorded
accounts (posted before `V15`, or the ledger's answer could not be paired with its legs) or a record
that does not match its legs. Treasury refuses rather than re-resolving the class against today's
map, and posts nothing; the deal stays `SETTLED`. To proceed:

1. Take the journal id from the error message and read that journal's lines from the ledger (its own
   record of where each leg landed, in sequence order). For a journal posted before ADR-0341 they are
   the fixed `a0000000-0000-0000-0000-00000000<code>` accounts; cross-check, do not assume.
2. Under a change record with a second reviewer, write `deal_journals.resolved_accounts` for that row:
   one `class|role|currency|glAccountId` line per leg, in the order the posting table emits the legs.
3. Retry the reversal (the failed attempt stored no command key, so the same `Idempotency-Key` works).

**Rollback.** With the flag `true` the legs carry no account, so an older ledger (below `V32`) refuses
every line. There is no rollback by configuration alone: set `OPENBANK_TREASURY_LEDGER_POST_BY_CLASS=false` and redeploy Treasury FIRST,
and only then roll the ledger back. Rolling the ledger back first fails every posting until Treasury is
redeployed.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
