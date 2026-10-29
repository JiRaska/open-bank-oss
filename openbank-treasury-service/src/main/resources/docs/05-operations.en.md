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
  accounting day. Before it does any work it marks itself in flight in `treasury_close_job_run` (V26).
  When it finishes it records the deals it assessed. It records a refusal when a deal failed to accrue,
  when the ledger answered `409 ACCOUNTING_DAY_CLOSED`, when its budget ran out with deals left, or
  when the pass failed. A closed-day refusal has its own category. If the in-flight marker cannot be
  written, the pass does not run.
- **Journal dating.** The ledger accepts postings only into an open day. Every treasury journal is
  therefore booked with `entryDate` = today's open Prague day and carries its economic day as
  `valueDate` (an accrual's day, a settlement's value date). This is the decision lending made. A
  catch-up for an earlier day can no longer hit `409 ACCOUNTING_DAY_CLOSED` because that day reached
  CUTOFF. Each journal is recorded in `deal_journals` with its value day (V26 `value_date`).
- **What a day is reported as.** The outbox hold and the DEAD check always run, whatever is due.
  - With the accrual off, the day is REFUSED if it has DEAD events, RAN if it has journals, and
    SKIPPED only if it has neither.
  - With the accrual on, if no pass has completed for the day, nothing is reported and the ledger
    keeps the day in CUTOFF.
  - Otherwise the latest pass decides: RAN, or REFUSED with its counted reasons.
  - The journal count is the number of `deal_journals` rows with that VALUE day: accruals,
    settlements, maturities and reversals.
- **Late journals.** A journal valued on a day that has already ended counts under that day and
  increments `openbank.treasury.close.late_journals`. The next tick re-reports the day with the new
  count, and the ledger stores a report for a closed day as late.
- **Holds.** Any of these holds the day:
  - a pass still in flight;
  - a `treasury.deal.settled|matured|reversed.v1` event associated with that exact deal and lifecycle
    journal value day, still PENDING, DISPATCHING or FAILED in `treasury_outbox` (a DEAD event makes
    the day REFUSED). An event without a matching dated journal holds its creation day;
  - an acknowledged lifecycle journal without its matching committed deal transition. Later lifecycle
    states do not invalidate an earlier committed transition;
  - a settlement or maturity valued on the day that was recorded after the latest completed accrual
    pass started. A pass filed under a later day counts, because after midnight it accrues the day too.
- **Stale threshold.** It is derived and never configured on its own. The pass checks
  `openbank.treasury.accrual.pass-budget` (20 minutes) before every deal and before every accrual day.
  It therefore cannot run past the budget by more than one posting, so `TreasuryCloseJobs.staleAfter` is
  the budget plus 30 minutes. An older marker means the pass died, and the day is reported REFUSED
  (`pass started and never completed`).
- **Healing.** A complete pass (no failed deal, none left over) has accrued every earlier day. It
  re-records each lookback day that is REFUSED or unrecorded as RAN, unless that day's own pass is
  still live.
- **Recorded before committed.** Settle, mature and reverse record the journal in its own transaction
  as soon as the ledger accepts it, before the deal's state change commits. If that commit rolls back,
  the journal is still counted, but the day is held until its lifecycle transition commits. A retry posts under the same idempotency key, gets the original journal
  back, and finds it recorded.
- **Posting intents (#13883).** Before contacting the ledger, settle, mature and reverse write a
  `deal_posting_intent` row (V27) keyed by the journal's idempotency key. The deal save completes it in
  the transaction of the state change. An OPEN intent means the ledger may hold a journal treasury has
  not applied, so it holds its value day. `PostingIntentReconcileScheduler` (every 5 minutes) claims
  intents older than `openbank.treasury.posting-intent.grace` (`FOR UPDATE SKIP LOCKED` plus a lease,
  so two pods never resume one) and re-runs the command under the same key; the ledger answers with
  the original journal. It resumes an intent as its stored actor only while it is younger than
  `max-resume-age` (4h, by age, not the calendar: the intent already pins its value day). An older
  one is escalated: it stays OPEN and holding its day, and is never claimed again, so escalated
  intents cannot starve resumable ones. While a deal has an open intent, or a reversal or maturity
  intent an operator closed (its journal may be in the ledger although the deal reads SETTLED), the
  accrual skips THAT deal only, re-checking before every day: every other deal accrues and the pass
  completes, while the day report counts the deferred deal as unaccrued (REFUSED, `deferred by an open
  or operator-closed posting intent`) until the command is re-issued. A reversal that finds a journal
  recorded after it read the deal's journals does not post (409; re-issue it). Each accrual day records
  an ACCRUE intent before its ledger call (completed when its journal is recorded), and the database
  admits one OPEN reversal, maturity or accrual intent per deal: a reversal decided while an accrual
  day is in flight answers 409, and an accrual while a reversal is in flight defers. `mature` and
  `reverse` also refuse (409) while the deal has another open or operator-closed posting intent, so a
  failed reversal cannot be followed by a maturity.
  The ledger's coded `409 ACCOUNTING_DAY_CLOSED` closes an intent REFUSED (no journal exists) when the
  intent is fresh or older than the grace; every other failure leaves it OPEN. The pod refuses to
  boot unless the grace is at least four times one ledger call (connect + read timeout, no retry).
- **A stuck intent.** `TreasuryPostingIntentStuck` fires when the oldest open intent is over 20
  minutes old (gauges `openbank.treasury.posting_intents.open` and `.oldest_age_seconds`, -1 when
  unreadable; promtool-tested). Read `last_error` on the row. Either re-issue the deal command through
  the API, which re-posts under the same key and completes the intent, or close it:
  `POST /api/v1/treasury/deals/{id}/posting-intent/close` with
  `{"event": "SETTLED|MATURED|REVERSED", "reason": "..."}`. That needs the OPA action
  `treasury.posting-intent.close` (approvers, never an agent), a person, and four eyes: not the
  posting's own actor nor the deal's approver. An intent younger than the grace or under a live
  reconciler claim answers 409 `POSTING_IN_FLIGHT` (retry later); 404 means none is open. The close
  sets CLOSED_BY_OPERATOR (who and why stay on the row) and releases the hold. A journal may still
  exist, so a later re-issue reopens the intent with its FIRST value day; it becomes the re-issuer's
  decision (their actor and time), so the grace and four eyes apply to it afresh. Every transition is
  appended to `deal_posting_intent_history`, whose triggers refuse UPDATE, DELETE and TRUNCATE from
  the service (not from the table owner, who can disable a trigger). A journal that lands after a
  close is recorded there as `JOURNAL_APPLIED_AFTER_CLOSE` with the prior status, counted in
  `openbank.treasury.posting_intents.applied_after_close`, alerted on
  (`TreasuryPostingIntentJournalAfterClose`: re-report that value day) and never written over the close.
- **Operating it.** Under `shadow` mode the ledger only counts a hold. Under `enforce` an operator
  reviews a REFUSED day and waives it on the ledger's day-end page.
