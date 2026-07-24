# ISO 20022 message catalog — what this platform speaks

> ISO 20022 is the financial-messaging standard replacing the legacy SWIFT MT catalog. The MT/MX
> coexistence window for cross-border payments closed on **2025-11-22** (MT103/202 retired on the
> correspondent-banking rails), with further deadlines through 2027 (MT101 in 2026-11, camt.110/111
> exceptions-&-investigations mandatory by 2027-11). ISO 20022-native is now table stakes, not a
> differentiator — this page states, per message, which services already speak it.
>
> **Positioning, not legal advice.** Every message type and service cited below exists in
> `origin/main` (derived by grepping `openbank-*/src/main` for the message identifiers). It maps the
> catalog to code; it is not a certification of scheme conformance. Related decisions:
> [ADR-0104](../adr/0104-production-faithful-payment-rails-iso-20022-and-scheme-simulator.md) (ISO 20022 rails),
> [ADR-0035](../adr/0035-multi-currency-account-statements.md) (statements),
> [ADR-0111](../adr/0111-payment-r-transaction-returns-pacs004.md) (returns).

## Why this page exists

The platform ships **VoP and SCT Inst before most of the market** (see [`ipr-vop.md`](ipr-vop.md))
and already speaks the ISO 20022 pacs/camt catalog on its clearing and statement paths — but that
was documented nowhere. Adopters evaluating an ISO 20022-native core had to read the source to find
out. This catalog closes that gap.

## Catalog — message → direction → service

### pacs — payments clearing & settlement (MX)

| Message | Name | Direction | Service |
|---|---|---|---|
| `pacs.008.001.08` | FI-to-FI Customer Credit Transfer | in/out | `clearing-simulator` (scheme leg) |
| `pacs.002.001.10` | FI-to-FI Payment Status Report | in/out | `clearing-simulator` |
| `pacs.004.001.09` | Payment Return | out | `sepa-payment` ([ADR-0111](../adr/0111-payment-r-transaction-returns-pacs004.md)) |

### camt — cash management (MX)

| Message | Name | Direction | Service |
|---|---|---|---|
| `camt.053.001.08` | Bank-to-Customer Statement | out | `statement-service` ([ADR-0035](../adr/0035-multi-currency-account-statements.md)) |
| `camt.054.001.08` | Bank-to-Customer Debit/Credit Notification | in | `clearing-simulator` |
| `camt.056.001.08` | FI-to-FI Payment Cancellation Request (recall) | in/out | payments recall path |

### MT — legacy SWIFT (retiring; kept for correspondent-banking interop)

| Message | Name | Service |
|---|---|---|
| `MT103` | Single Customer Credit Transfer | `swift-service`, `sepa-payment`, `sepa-instant`, `domestic-payment`, `transaction-service` |
| `MT202` | General Financial Institution Transfer | `swift-service` |
| `MT199` | Free-format message | `swift-service` |
| `MT900` / `MT910` | Confirmation of Debit / Credit | `swift-service` |
| `MT940` / `MT950` | Customer / Statement Message | `swift-service`, `statement-service` |

## Honest gaps (do not overstate to an auditor)

- **The MX catalog is partial and partly scheme-simulated.** `clearing-simulator` speaks the
  pacs/camt clearing messages against a *simulated* scheme gateway (ADR-0104), not a live CSM/RT1
  connection — the message shapes are real, the counterparty is not.
- **MT is being retired, not extended.** MT103/202 are out of the correspondent-banking coexistence
  window (2025-11); they remain here for interop and internal representation, but new work is MX.
- **No pain.\* (customer-to-bank initiation) in the catalog yet** — payment initiation is over the
  REST/PSD2 surface, not pain.001/pain.002 messaging. A recorded position ADR is warranted if a
  pain.\* channel is ever added.
- **CBPR+ / SWIFT go-live is not claimed.** camt.110/111 exceptions-&-investigations (mandatory by
  2027-11) are not implemented.

## Maintenance

This catalog is hand-maintained today. It is derived from a mechanical grep
(`grep -rhoE 'pacs\.|camt\.|pain\.|MT[0-9]{3}' openbank-*/src/main`); re-run it when a payments or
clearing service changes to keep the table honest, or generate it in CI (a follow-up worth doing
once the MX surface stabilises).
