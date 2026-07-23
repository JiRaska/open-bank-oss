# ISO 20022 message catalog

> Where each ISO 20022 (and legacy SWIFT MT) message type lives in this platform.
>
> **This catalog is derived from the code on `origin/main`**, not from a spec wish-list. Every
> message type below was confirmed present in tracked source (`git grep`) before being listed;
> types that are only *referenced* but not *modelled* are called out explicitly rather than
> padded into the table. See [ADR-0104](../adr/0104-production-faithful-payment-rails-iso-20022-and-scheme-simulator.md)
> for the production-faithful-rails decision that grounds this pipeline.

## Shared ISO 20022 library

The XSD-validated builders and readers are centralised in the domain library, package
`com.openbank.libs.iso20022` (`openbank-libs-domain`), with bundled schemas under
`src/main/resources/iso20022/schemas/`:

| Schema shipped | File |
|---|---|
| `pacs.008.001.08` | `Pacs008Builder.kt` / `Pacs008Reader.kt` |
| `pacs.002.001.10` | `Pacs002Builder.kt` / `Pacs002Reader.kt` |
| `pacs.004.001.09` | `Pacs004Builder.kt` / `Pacs004Reader.kt` |
| `camt.054.001.08` | `Camt054Builder.kt` |
| `camt.056.001.08` | `Camt056Builder.kt` |

Structural validation is performed by `Iso20022Validator.kt` against the bundled `.xsd` files.

## Message → where it lives

| Message | ISO 20022 / MT name | Role | Where it lives (services) |
|---|---|---|---|
| **pacs.008** | FI to FI Customer Credit Transfer | Outbound/inbound credit-transfer instruction | `openbank-libs-domain` (builder/reader), `openbank-clearing-simulator`, `openbank-domestic-payment`, `openbank-sepa-payment`, `openbank-sepa-instant`, `openbank-swift-service` |
| **pacs.002** | FI to FI Payment Status Report | Accept/reject status for a submitted payment | `openbank-libs-domain`, `openbank-clearing-simulator`, `openbank-domestic-payment`, `openbank-sepa-payment`, `openbank-sepa-instant`, `openbank-swift-service` |
| **pacs.004** | Payment Return | R-transaction return of a settled SCT | `openbank-libs-domain`, `openbank-clearing-simulator`, `openbank-sepa-payment` ([ADR-0111](../adr/0111-payment-r-transaction-returns-pacs004.md)) |
| **camt.056** | FI to FI Payment Cancellation Request | Recall / cancellation request | `openbank-libs-domain` (`Camt056Builder`) |
| **camt.053** | Bank to Customer Statement | End-of-day account statement | `openbank-statement-service`, `openbank-customer-edge` |
| **camt.054** | Bank to Customer Debit/Credit Notification | Per-entry debit/credit advice | `openbank-libs-domain`, `openbank-clearing-simulator`, `openbank-transaction-service` |

## Legacy SWIFT MT / MX (correspondent banking)

`openbank-swift-service` models the correspondent-banking message set and emits both legacy MT and
ISO 20022 MX (pacs) variants. The MT types enumerated in its domain model (`SwiftMessage.kt`) are:

| MT | Purpose |
|---|---|
| **MT103** | Single customer credit transfer |
| **MT199** | Free-format message (customer transfer group) |
| **MT202** | General financial-institution transfer |
| **MT900** | Confirmation of debit |
| **MT910** | Confirmation of credit |
| **MT940** | Customer statement message |
| **MT950** | Statement message |

On the MX side, swift-service builds `pacs.008` and reads `pacs.002.001.10`, i.e. the ISO 20022
equivalents of the MT103/MT202 credit-transfer and status flows. Governing decisions:
[ADR-0104](../adr/0104-production-faithful-payment-rails-iso-20022-and-scheme-simulator.md) (real
ISO 20022 messages),
[ADR-0108](../adr/0108-rail-settlement-via-transaction-service.md) (rail settlement path).

## Referenced but not modelled (honest note)

- **pain.001** (Customer Credit Transfer Initiation) is **not** implemented as an XML message in
  this repository. It appears only as a documentation reference — a comment in
  `openbank-customer-edge` noting the ISO 20022 `Max35Text` constraint on `endToEndId`, and a
  mention in [ADR-0104](../adr/0104-production-faithful-payment-rails-iso-20022-and-scheme-simulator.md).
  Payment initiation on this platform is a REST/JSON API at the customer edge, not a pain.001
  submission. Do not list pain.001 as a supported message.
- **camt.052** (intraday report) and **pain.002** (initiation status report) have **no**
  occurrences in the codebase and are intentionally omitted.

## References

- [ADR-0104 — Production-faithful payment rails: real ISO 20022 + scheme simulator](../adr/0104-production-faithful-payment-rails-iso-20022-and-scheme-simulator.md)
- [ADR-0108 — Rail settlement runs through transaction-service](../adr/0108-rail-settlement-via-transaction-service.md)
- [ADR-0111 — SEPA R-Transaction returns via pacs.004](../adr/0111-payment-r-transaction-returns-pacs004.md)
- [ADR-0103 — Transaction rail & instruction type captured at origination](../adr/0103-transaction-rail-and-instruction-type-at-origination.md)
- [Instant Payments Regulation compliance position](ipr-vop.md)
