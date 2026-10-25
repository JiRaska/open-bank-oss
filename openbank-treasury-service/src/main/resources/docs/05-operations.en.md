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

## Fixing refusal after a deal state change

Before applying a fixing, the repository re-reads the deal state and schedule version while holding
the deal row lock (`FOR UPDATE`). `CANCELLED`, `REVERSED` and `DRAFT` cause a typed
`DealNotLiveForFixingException` before any cash-flow update or `treasury.cashflow.fixed.v1` outbox
write. This refusal increments `openbank.treasury.fixings{outcome="deal_not_live"}`; `FixingService`
skips the deal without counting an index failure. `MATURED` remains eligible under the existing
state filter, and the REST response shape is unchanged.

`FixingApiIT` covers cancellation committed after the due-flow read and before the locked write:
the response reports zero fixed flows and failures, projected flows remain unchanged, no fixing
event is written, and the refusal counter increases by one.

## Day-close participation (ADR-0342, #13167)

Treasury is a ledger day-close participant under the name `treasury`.
`TreasuryCloseParticipationScheduler` runs at minute 40 of every hour, Europe/Prague
(`openbank.treasury.close.report-cron`). Each run re-offers the last `openbank.treasury.close.lookback-days`
(3) completed accounting days through `PUT /api/v1/ledger/accounting-days/{date}/participants/treasury`.
It reports on treasury's own m2m identity (`service-account-openbank-treasury`), which the ledger
admits under `ledger.close.participate` for this participant only.

- **Evidence.** Each pass of the hourly accrual (`InterestAccrualScheduler`) works as of one Prague
  accounting day. Before it does any work it marks itself in flight in `treasury_close_job_run` (V21).
  When it finishes it records the deals it assessed. It records a refusal when a deal failed to accrue,
  when the ledger answered `409 ACCOUNTING_DAY_CLOSED`, when its budget ran out with deals left, or
  when the pass failed. A closed-day refusal has its own category. If the in-flight marker cannot be
  written, the pass does not run.
- **What a day is reported as.** With the accrual off, the day is SKIPPED. If no pass has completed
  for the day, nothing is reported, so the ledger keeps the day in CUTOFF. Otherwise the latest pass
  decides: RAN, or REFUSED with its counted reasons. The journal count is the number of
  `deal_journals` rows posted in that Prague day: accruals, settlements, maturities and reversals.
- **Holds.** A pass still in flight holds the day. So does a `treasury.deal.settled|matured|reversed.v1`
  event created that day while it is still PENDING, DISPATCHING or FAILED in `treasury_outbox`. A DEAD
  event makes the day REFUSED (`journal event(s) never delivered`).
- **Stale threshold.** It is derived and never configured on its own. A pass stops starting deals once
  `openbank.treasury.accrual.pass-budget` (20 minutes) is spent, so `TreasuryCloseJobs.staleAfter` is the
  budget plus 30 minutes. An older marker means the pass died, and the day is reported REFUSED
  (`pass started and never completed`). The next complete pass for the same day replaces it.
- **Operating it.** A day REFUSED for `ACCOUNTING_DAY_CLOSED` means accrual tried to post into a day
  the ledger had already closed. Under `shadow` mode the ledger only counts the hold. Under `enforce`
  an operator reviews the day and waives it on the ledger's day-end page.
