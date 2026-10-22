# Treasury accounting and close participation

This describes the implementation on the ADR-0344 branch. It does not establish
review approval, a deployed image, successful payment release, or permission to
remove a deployment hold. Required reviews, dependency merge order and runtime
verification remain separate delivery steps.

## Instrument accounts

`AccountingRole` adds `PREMIUM_DISCOUNT`, `FAIR_VALUE`, `REVALUATION_RESERVE`,
`REALISED_GAIN`, `REALISED_LOSS`, `UNREALISED_GAIN`, `UNREALISED_LOSS`, `MEMO` and
`MEMO_CONTRA`. The V34 migration adds instrument accounts and accounting-class
mappings in CZK and EUR. Treasury resolves journal legs by accounting class, role
and currency; the ledger owns the GL account mapping. V34 checks that the mapping
contains 113 rows and that each mapping currency matches its GL account.

Securities have distinct amortised-cost, FVOCI and FVTPL principal accounts.
Forward commitments and collateral use balancing memo/contra pairs; they do not
post to a real balance-sheet account merely to represent an off-balance commitment.
Treasury still submits journals through the ledger posting API. This reference
data is not evidence that a journal or external settlement actually occurred.

Sources: [accounting roles](../../kotlin/com/openbank/ledger/domain/model/AccountingClassMapping.kt)
and [V34 migration](../db/migration/V34__treasury_instrument_accounts.sql).

## Participant reports and operator decisions

The branch declares `treasury` alongside `interest` in
`openbank.ledger.close.participants`, with one permitted verified service-account
principal for each participant. `PUT
/api/v1/ledger/accounting-days/{businessDate}/participants/{participant}` accepts
only `RAN`, `SKIPPED` or `REFUSED`. A participant cannot submit `WAIVED` or
`MISSING`. The shared `CloseOutcome` describes reports; `ParticipantDayStatus`
describes the read response, which can additionally show those two states.

`WAIVED` is an explicit human operator decision through the separate `/waiver`
endpoint. Its reason is mandatory; it is valid only for an existing CUTOFF day,
and cannot supersede a RAN report. A non-RAN retry does not undo an existing
waiver. Reports arriving after TIED_OUT or LOCKED are retained as late reports
and do not move the accounting day backwards.

Missing reports and the latest REFUSED reports make completeness false.
`LEDGER_CLOSE_PARTICIPANTS_MODE` defaults to `shadow`: the scheduler records
would-hold decisions and continues, rather than enforcing a hold. `enforce` holds
the CUTOFF-to-TIED_OUT transition while participation is incomplete; `off`
disables this participation check. This document does not authorize changing the
mode or claim that the shadow observation prerequisite has been met.

Sources: [resource and request validation](../../kotlin/com/openbank/ledger/infrastructure/rest/CloseParticipationResource.kt),
[participation rules](../../kotlin/com/openbank/ledger/application/usecase/CloseParticipationService.kt),
[scheduler](../../kotlin/com/openbank/ledger/infrastructure/schedule/AccountingDayScheduler.kt)
and [checked-in configuration](../application.yaml).
