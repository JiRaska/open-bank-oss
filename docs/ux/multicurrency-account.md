# UX behaviour spec — Multi-currency account

> Behavioural contract for the design system. Describes **what is domain truth** and **how it must
> render**, not pixels. Source of truth for the domain remains ADR-0024 (model) and ADR-0025
> (FX / ledger). When this doc and an ADR disagree, the ADR wins.

## Mental model (read this first)

**One account = one IBAN = one row in the account list.** Beneath it live N **currency pockets**:
exactly one **primary** pocket (cannot be closed) plus zero or more **secondary** pockets. The IBAN
does **not** encode currency — the currency of an incoming payment selects the pocket.

Consequence for the DS: a multi-currency account is **not** N accounts side by side. It is **one
account with an internal currency switch**. (Today's admin-UI models each currency as a separate
`Account`; that is the gap this spec closes.)

```
Account  CZ65 5051 0000 0012 3456 7890   CURRENT · primary CZK · ACTIVE
├─ pocket  CZK  (primary)   available 50 000,00   booked 52 000,00 …
├─ pocket  EUR  (secondary) available  1 500,00   booked  1 800,00 …
└─ pocket  USD  (secondary, FROZEN)     …
   Σ  ≈ 88 240 CZK  (indicative, ČNB rate)
```

## How pockets are kept (what to render)

Each pocket is an independent wallet. Pockets are **never netted against each other**.

| Field | Source | Render |
|---|---|---|
| `currency` + `isPrimary` | pocket (account-service) | pocket title; badge "Primary / Primární" |
| `status` ACTIVE / FROZEN / CLOSED | pocket | semantic `.badge` (success / warning / muted); FROZEN/CLOSED dimmed + lock |
| `availableBalance` | balance (balance-service) | **primary figure**, accent-highlighted |
| `currentBalance` (booked) | balance | secondary line |
| `reservedBalance` / `pendingBalance` | balance | secondary lines |
| `arrangedOverdraftLimit` | balance | primary pocket only (Phase 1); shown under available |

API shape: `GET /balances/{accountId}` (balance-service) already returns a **list**, one entry per
currency. The pocket roster comes from `GET /accounts/{accountId}/pockets`. Join on `currency`.

## Consolidated balance — regulatory rule, not cosmetics

ADR-0024 §49–51: the sum of pockets converted into the primary currency is **indicative only**
(computed at the ČNB reference rate). The DS **must**:

- label it explicitly "indicative / orientační",
- never present it as spendable,
- **never** feed it into a funds-availability or overdraft decision (those are per-pocket).

This is a compliance constraint, so it belongs in the contract, not a footnote.

## Switching between pockets

- A **segmented control / tabs** above the balance panel; one segment = one pocket
  (`🇪🇺 EUR`, `🇨🇿 CZK` — flags/symbols come from `src/lib/currency-meta.ts`).
- Default selection = the primary pocket.
- FROZEN / CLOSED pockets stay visible but dimmed and non-default.
- A leading "Total (indicative)" segment is optional but, if shown, must carry the indicative label.

## Pocket lifecycle (state logic the DS must reflect)

A pocket does not appear by magic — there are exactly three ways in and one way out:

1. **Explicit open** — `POST /accounts/{id}/pockets` → empty `ACTIVE` pocket in that currency.
2. **Incoming payment in a new currency** — resolved by `PocketRouter` per the product's policy:
   - `AUTO_CREATE` → open a pocket, credit it, no FX;
   - `CONVERT_TO_PRIMARY` → convert into the primary currency (FX 4-leg per ADR-0025), credit primary;
   - `REJECT` → return the payment.
   `GET /accounts/{id}/pockets/resolve?currency=GBP&policy=…` returns the `outcome` up front, so the
   DS can preview "incoming GBP will convert to CZK" before money moves.
3. **Close** — secondary pockets only, `DELETE /accounts/{id}/pockets/{currency}`. The primary pocket
   closes only by closing the whole account.

## States the DS must have visuals for

- pocket `ACTIVE` / `FROZEN` / `CLOSED`
- account `PENDING_ACTIVATION` / `ACTIVE` / `DORMANT` / `FROZEN` / `CLOSED`
- balance: negative available within `arrangedOverdraftLimit` vs. beyond it (the latter is an alert)
- zero-balance pocket (valid — e.g. freshly auto-created)
- single-currency account (the common case: just the primary pocket, switch collapses to nothing)

## Money formatting (one source of truth)

Introduce a single `<Money amount currency />` primitive. Today formatting is inline in ≥5 places and
inconsistent (`en-US` vs `cs-CZ`). Rules:

- locale follows the UI language (`cs-CZ` / `en-US`), currency code/symbol from `currency-meta.ts`;
- fraction digits from the currency (CZK 0, EUR/USD 2) — do not hard-code 2;
- monospace tabular figures for column alignment;
- never mix currencies in one summed figure unless it is the labelled indicative total.

## Gap list (what UI needs before this can render)

1. `src/types/index.ts` — `AccountBalance` must become a **list of pockets**, not one `currencyCode`.
2. `accountApi` — add `listBalances(accountId)` against balance-service `GET /balances/{accountId}`.
3. `<Money>` primitive (see above).
4. Pocket switcher + multi-pocket balance panel on `src/app/accounts/[id]/page.tsx`.

## References

- ADR-0024 — multi-currency account model (one IBAN, currency pockets)
- ADR-0025 — FX revaluation, per-currency ledger balancing (4-leg conversion entries)
- Domain: `openbank-account-service` `CurrencyPocket.kt`, `PocketRouter.kt`; `openbank-balance-service` `Balance.kt`
