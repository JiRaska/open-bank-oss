# openbank-lending-service — service notes

## Engineering notes

- **`source_service` for this producer is `"lending"`, not `"lending-service"` — a settled
  decision (#5902), not drift awaiting cleanup. Grouping `audit_entries` by `source_service`
  splits this one producer into THREE aliases unless you account for it.** Every other producer in
  the fleet emits its module directory name minus the `openbank-` prefix (#5256), and
  `check-source-service-convention.py` now enforces exactly that; lending is the one deliberate
  exception, baselined in that script with this reason. All nine of its outbox event types emit
  the literal `"lending"` from `LendingService.kt`, `OriginationDecisionService.kt` and
  `TerminationService.kt`.

  The aliases exist because attribution changed underneath the events twice, so the value in a row
  depends on *when* it was written, not on anything about the event:

  | window | the 3 early types | the 6 later types |
  |---|---|---|
  | 2026-07-31 → 2026-08-09 | `lending` | `unknown` |
  | 2026-08-09 → 2026-08-18 | `lending` (EVENT) | `lending-service` (TOPIC) |
  | 2026-08-18 → now | `lending` | `lending` (EVENT) |

  - **the 3 early types** — `credit.application.transition`, `credit.decision.evaluated`,
    `credit.loan.transition` — carried `sourceService` from the day they shipped (ADR-0214 /
    ADR-0215, 2026-07-31 and 2026-08-01), so they read `lending` for their whole history.
  - **the 6 later types** — `loan.disbursed`, `loan.interest_accrued`, `loan.written_off`,
    `loan.rescheduled`, `loan.stage_changed`, `loan.provisioned` — carried no `sourceService` at
    all until PR #5399 (merged 2026-08-18). Before #4270 (2026-08-09) `AuditConsumer` read the
    field or defaulted, so they stored `unknown`; between #4270 and #5399 they resolved through
    `TopicAttribution`, whose table maps `openbank.lending.events` to `lending-service`; after
    #5399 the producer's own value wins and they read `lending` like the rest.

  So `source_service = 'lending-service'` exists **only** for those six event types and **only**
  inside that nine-day window. It is a bounded artefact, which is what made keeping `"lending"`
  the cheap option.

  Any query grouping by `source_service` — the documented way to detect a producer that has
  stopped emitting, or one that never started — must treat `lending`, `lending-service` and
  (before 2026-08-09) `unknown` as one producer. `unknown` is the weakest of the three: it is the
  fleet-wide "nobody said" sentinel, so it is not lending's alone, and only the `event_type`
  narrows it. Establish the real row counts before relying on any of this:

  ```sql
  SELECT source_service, source_service_source, event_type,
         min(recorded_at), max(recorded_at), count(*)
  FROM audit_entries
  WHERE source_service IN ('lending', 'lending-service', 'unknown')
  GROUP BY 1, 2, 3
  ORDER BY 3, 4;
  ```

- **The rows cannot be rewritten, so "we did not backfill" is a property of the table, not an
  omission anyone can fix later.** `audit_entries` is append-only *at the database* — V2 installs
  `no_update_audit` / `no_delete_audit` rules (`DO INSTEAD NOTHING`), so an `UPDATE` normalising
  the spelling silently affects zero rows and reports success. Even with the rules removed it
  would be the wrong act: `source_service` is one of the fields hashed into the ADR-0031 /
  ADR-0133 `record_hash` chain, so rewriting it breaks tamper-evidence for every affected row and
  every row after it. Reconciliation belongs in the query (or a view), never in the data. That is
  half the reason the decision above is the cheap one.

- **Do not "fix" the spelling to match the convention.** A rename touches nine shipped event types
  and introduces a *fourth* boundary in `audit_entries` — a second split, on top of a split that
  can never be repaired for the reason above. If the decision is ever revisited, removing lending's
  entry from `check-source-service-convention.py`'s `BASELINE` is the visible act that reopens it;
  the entry pins the `(module, value)` pair, so drifting to some third spelling fails the gate
  today regardless.

- **The engine's DSTI is NOT the CNB definition, and both numbers now exist.**
  `OriginationDecisionService.affordabilityRatios` is the single definition of the affordability
  ratios — the ASSESSMENT leg reads it, and so does the credit-risk read side, so a console figure
  and an engine figure cannot diverge. It returns three numbers: `dsti` (new installment over
  verified income, what `PolicyAttribute.DSTI` carries into the affordability table),
  `dti`, and `dstiIncludingExistingDebt` (adds `existingDebtServiceMonthly`, the CNB/EBA
  total-debt-service definition). **Only the first two reach the engine.** `existingDebtServiceMonthly`
  is collected at intake and persisted, and no starter rule tests it, so an applicant already
  servicing debt meets the `DSTI ≤ 0.45` floor with headroom the bank does not see. Deliberate:
  moving the floor to total debt service tightens credit and is a policy decision under ADR-0213 D4,
  not a refactor. Whether to take it is #8894. Do not "fix" it by editing the ratio — the read side
  would silently start disagreeing with the decisions already pinned in the evidence chain.

- **The engine cannot DECLINE for adverse bureau data — the port is a no-op.** `CreditBureauPort`'s
  default binding returns no adverse flag, so `CUSTOMER_TYPE` is `STANDARD` for every applicant and
  `starter-ex-adverse` (the only EXCLUSION rule) can never match. A decline rate near zero is a
  statement about the missing feed, not about the applicants. The credit-risk console says so on the
  page, and its per-rule hit count shows the rule at zero. Same shape as the PD/LGD placeholders:
  `RiskParameterSource`'s no-op returns flat constants and stamps `model_version` on every
  provisioning row, which is why an ECL figure must always be quoted with that version.

