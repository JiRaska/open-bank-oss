# Branch payment attribution and clearing (ADR-0380 B2)

Branch reversals are durable counterpayments. Original status, the pending reversal and both
outbox events commit atomically under the original version check. Two racing reversal keys
cannot refund the same original twice. The reversal swaps the original immutable accounts and
booking contexts; current Account or Catalogue availability cannot change its route. Both
one-sided cash flows and same-book/inter-branch transfers use the existing branch recovery sweep
and idempotent Ledger receipt. A commit-before-dispatch crash needs no further client request.
`REVERSED` records acceptance of that durable intent; the linked reversal's `COMPLETED` event
is the settlement evidence. Replaying a reversal key for another branch original is rejected.
Rollback retains pending reversals, original links, pinned contexts and outbox evidence;
recovery readers use the existing transaction schema and workflow type.

Transaction reads and pins the immutable source/target Account booking contexts before committing its row and initiated outbox event. Both existing customer legs must have reviewed BRANCH contexts in the same bank; unknown legacy/home attribution and branch FX routes are refused. Every branch leg must also carry an Account-owned denomination equal to the payment currency; a different or absent denomination is refused before the transaction/outbox is written, including incoming credits with no cover hold. Country selection never chooses a GL account.

Branch payments return the persisted PENDING state after asynchronous dispatch to a separate durable Temporal workflow type. A suspend recovery sweep restarts dispatch after a commit-before-start outage using the same workflow id. Posting, hold placement and completion remain retryable across worker outages. A branch reservation has no TTL: it stays covered until the matching booked projection or an explicit release; an uncertain Ledger response must never reopen the funds to spending. The existing stuck-saga control surfaces unresolved operations. Existing regular payment histories and their bounded activity policy remain unchanged.

Ledger accepts only the named Transaction M2M identity on POST /api/v1/journals/branch-payments. One database transaction contains the key/digest receipt, both branch journals and their outbox. The source book debits deposit control and credits inter-branch clearing; the destination debits clearing and credits deposit control. Same-book transfers use two deposit legs; one-sided branch payments retain the branch on their cash-clearing journal. Every retry binds the bank, account/book pair, amount/currency, dates, actor and synthetic origin. Concurrent calls return the original one- or two-journal receipt.

GET /api/v1/journals/branch-books reports native-currency clearing positions and checks elimination per individual payment. Balance persists these positions and refuses a complete reconciliation if either a payment pair or bank-wide clearing does not eliminate, independently of deposit agreement and book trial balance. Original booked events retain each journal's immutable book. Synthetic entries stay outside BUSINESS control.

Deploy Ledger V48/readers and Account context readers before new Transaction V24 writers; keep the existing projection/launch restrictions. Rollback stops new writers/dispatchers and retains reservations, transaction contexts, receipts, journals, outbox and reconciliation evidence. Do not expire an uncertain branch reservation or manufacture historic attribution to clear a control. Home-book migration, branch FX, wider servicing and production launch require their own evidence.

Synthetic branch requests use `/api/v1/journals/branch-payments/synthetic`, which requires authenticated synthetic origin. Either trusted request carrier preserves that origin; an absent/refused decision fails before a real journal can be recorded. Receipt replay cannot cross synthetic and real origins.

Branch admission and Ledger share `openbank-libs-market.BranchPaymentCurrency`: only the currently GL-backed CZK, EUR, USD and GBP routes are admitted. A different valid ISO denomination is refused before a transaction, outbox event, workflow or cover hold is created. Adding a country or product currency does not enable a posting route; the shared capability, exhaustive Ledger mapping and actual GL accounts must be deployed together, readers before writers. Regular unattributed payment behavior is unchanged.

A confirmed insufficient-funds refusal while reserving cover ends the branch payment in FAILED. Technical failures and uncertain outcomes still retry under the original operation key.
