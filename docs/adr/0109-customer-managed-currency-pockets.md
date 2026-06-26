# 109. Customer-managed currency pockets (add / remove)

Date: 2026-06-22
Status: Accepted
Author(s): Jiří Raška

## Context

An OpenBank account already holds money in multiple **currency pockets** — a
per-currency sub-balance under one account. The backend models this fully today:

- `account-service` has the `CurrencyPocket` domain + `account_pockets` table, with
  one `is_primary` pocket per account (CZK) and a unique `(account_id, currency)`
  constraint.
- It exposes the lifecycle over REST: `GET /api/v1/accounts/{id}/pockets`,
  `POST …/pockets` (open), `DELETE …/pockets/{ccy}` (close), and a `…/pockets/resolve`
  routing helper.
- The domain enforces that **the primary pocket cannot be closed** (`close()` →
  *"close the account instead"*). Opening a pocket initialises a zero balance in
  `balance-service` (one balance row per `(account_id, currency)`).
- `fx-service` (and a customer-facing `POST /customer/v1/fx/rates/{base}/{quote}` at
  the edge) provides conversion rates. `product-catalog` already has a
  `MultiCurrencyConfig.supportedCurrencies` allow-list on the product — currently
  **defined but not enforced** by account-service (any valid ISO-4217 code is accepted).

So the capability exists end-to-end on the backend. **What is missing is customer
reach:** the customer-edge exposes none of the pocket endpoints, and the app has no UI
to add or remove a currency. The app *does* already have the building blocks — an
`ObPocket`/`ObAccount.pockets` model, a curated currency registry (`OB_CUR`: CZK, EUR,
USD, GBP), FX helpers, a **read-only** pocket picker (`ObAccountPocketOverlay`), and a
pocket-to-pocket transfer/exchange screen (`PocketTransfer`). The user can *switch
between* pockets but cannot *open or close* one.

CZK is the mandatory primary currency. Removing it is an account-closure concern, not
a pocket operation, and is explicitly out of scope here.

## Decision

**We will expose the existing pocket lifecycle through the customer-edge as a thin,
ownership-checked proxy, and add a "Manage currencies" surface in the app reachable
from the pocket picker — so a customer can open and close non-primary currency pockets
themselves.** No new backend capability is built; this is exposure + UX + a few
safety rules.

### 1. Edge endpoints (thin proxy, ADR-0065 pattern)

Mirror the account-service routes under `/customer/v1`, each ownership-checking the
account against the JWT party (same guard as `/transactions`, `/balances`) and
injecting `requestedBy` from the JWT `sub`:

- `GET    /customer/v1/accounts/{accountId}/pockets`
- `POST   /customer/v1/accounts/{accountId}/pockets`            (body `{ "currencyCode": "EUR" }`)
- `DELETE /customer/v1/accounts/{accountId}/pockets/{currency}`
- `GET    /customer/v1/accounts/{accountId}/pockets/resolve?currency=…`  (UX preview)

**No SCA** on add/close — opening or closing a pocket moves no money (ADR-0021 gates
*payments*, not account servicing). The optional convert-before-close (below) reuses
the existing own-account transfer, which is PSD2 RTS Art. 15 SCA-exempt.

### 2. Authoritative supported-currency list

The set of openable currencies is owned by **`product-catalog`
(`MultiCurrencyConfig.supportedCurrencies`)** and enforced **at the edge** before
forwarding an open request (reject unsupported with a clear 422). The app fetches this
list (surfaced on the account response or a small `/customer/v1/currencies` endpoint)
rather than hard-coding it. *Interim*: until the catalog wiring lands, the edge may
fall back to a curated set (CZK, EUR, USD, GBP, CHF) so the feature can ship; the app
already carries this set in `OB_CUR`.

### 3. Primary pocket is locked

CZK (the primary) is never removable from this surface. The backend already enforces
it; the app hides the remove affordance on the primary and labels it "Hlavní měna".
Closing the primary = closing the account (a separate flow, not built here).

### 4. Safe close: no orphaned balances

The backend has **no zero-balance guard** on close (a closed pocket's balance survives
in balance-service, unreachable). We will not expose that footgun. The edge
**pre-checks the pocket balance** (via balance-service) and refuses to close a pocket
with a non-zero balance; the app routes the user to **convert the remainder to CZK
first** (an explicit, confirmed own-account FX move via the existing `PocketTransfer`
flow), then closes. Never a silent auto-sweep — the customer always sees and approves
the conversion.

### 5. UX — manage from the pocket picker

The pocket picker is where users already switch currency, so it is the most
discoverable home for management. `ObAccountPocketOverlay` gains a "Spravovat měny"
(Manage currencies) affordance opening an `ObSheet` that:

- lists current pockets — CZK shown with a locked "Hlavní" chip, secondary pockets
  with a remove action (swipe / trailing button) gated per §4;
- offers **"Přidat měnu"** → a list of supported, not-yet-open currencies (flag + name
  from the catalog/`OB_CUR`); one tap opens the pocket (optimistic insert, zero
  balance);
- reuses existing components: `ObSheet`, `ObFormRow`, `ObPocketChip`, the FX helpers,
  and `PocketTransfer` for the convert-before-close path.

This keeps the feature small, fast to build, and consistent with the design system.

## Alternatives considered

- **A — Auto-create pockets only on first incoming FX payment** (`MissingPocketPolicy
  .AUTO_CREATE`), no manual UI. Pros: zero UI. Cons: the customer can't pre-open a
  pocket before sending/receiving, has no visibility or control, and can't close one.
  Rejected as the *only* mechanism (the policy remains useful for inbound routing).
- **B — Allow closing a pocket with a non-zero balance** (backend's current behaviour).
  Cons: orphans the customer's money in a closed, unreachable pocket. Rejected.
- **C — Silent auto-sweep to CZK on close.** Cons: silently FX-converting a customer's
  balance is a surprise and a conduct risk. Adopted only as an *explicit, confirmed*
  step, never silent.
- **D — Hard-code the currency list in the app.** Cons: drifts from what the bank
  actually offers per product. Rejected as the long-term source; acceptable only as a
  documented interim.
- **E — Put management in Profile/Settings.** Cons: far less discoverable than the
  pocket picker, where currency switching already lives. Rejected.

## Consequences

**Positive**
- Ships a visible, self-service feature on top of capability the backend already has —
  small surface, mostly exposure + UX.
- Discoverable (lives where users switch currency); reuses the design system.
- Safe close semantics — no orphaned balances; the customer approves every conversion.
- Authoritative currency list from product-catalog; CZK primary protected end-to-end.

**Negative**
- The edge must pre-check balance before close (an extra balance-service call) and
  consult product-catalog for the allow-list — modest new edge logic.
- The convert-before-close path adds a step for non-empty pockets (intentional, for
  safety).
- Product-catalog allow-list enforcement is new wiring; an interim curated list is a
  stop-gap.

**Neutral**
- No SCA on pocket add/close (no money movement); the convert step reuses the
  Art.15-exempt own-account transfer.
- The `…/pockets/resolve` endpoint is exposed for UX previews but is optional for v1.

## Compliance impact

- **PSD2:** opening/closing a pocket is account servicing, not a payment — no SCA. The
  optional convert-before-close is an own-account transfer (PSD2 RTS Art. 15 exempt),
  consistent with ADR-0021.
- **GDPR:** no new personal data; pocket currency is account metadata.
- **CNB / AML:** multi-currency holdings are already modelled and screened; this only
  changes who can open/close them (the customer, audited via `requestedBy`).
- **PCI DSS:** not applicable (no card data).

## References

- ADR-0021 — SCA decoupled device approval (SCA gates payments, not account servicing)
- ADR-0065 — Customer edge as a thin authorizing proxy
- ADR-0103 — Transaction rail & instruction type (sibling: making multi-currency money movement legible)
- account-service `CurrencyPocket` / `account_pockets` / pocket REST endpoints
- product-catalog `MultiCurrencyConfig.supportedCurrencies`
- openbank-app — `ObAccountPocketOverlay`, `OB_CUR`, FX helpers, `PocketTransfer`
