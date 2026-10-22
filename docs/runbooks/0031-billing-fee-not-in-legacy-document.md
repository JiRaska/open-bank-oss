# Billing: a FEE price component is not billed (NOT_IN_LEGACY_DOCUMENT)

Alert: `BillingFeeComponentNotBilledNotInLegacyDocument` (warning, billing). It fires when
`openbank_billing_fee_component_dropped_total{reason="NOT_IN_LEGACY_DOCUMENT"}` grew in the last 6h.

## What it means

A published catalog revision tags a price component with `role: FEE`, and the same revision also
embeds a v1 product document (`attributes.legacyDocument`). Billing's idempotency key for a fee is
(cycle, account, fee id, currency). To keep that key stable when an account is re-pinned from an
older revision mid-cycle, billing charges a FEE on such a revision only when its code is one the v1
document derives (the catalog's price code for a v1 fee id), and it uses that v1 fee's id.

A FEE whose code the v1 document does not derive has no id that is stable across the cutover. Billing
does not guess one, because a fresh id would charge the same fee a second time in that cycle. It drops
the component instead, so **the fee is never charged**. That is lost revenue, repeated on every
assessment under the revision, until the revision is corrected. It is not a customer harm and not a
ledger break: nothing was posted.

## Find the revision (read-only)

The billing log line names the revision and the component code: search billing-service logs for
`fee component <code> is not billable: NOT_IN_LEGACY_DOCUMENT`. Then read the revision from the catalog
(`GET /api/v2/revisions/{id}`) and compare `content.prices[].code` (the `role: FEE` ones) with the fee
ids in `content.attributes.legacyDocument`.

## Remedy (a product change, through the catalog's normal maker-checker publication)

Choose one. Both are new revisions, because a published revision is immutable.

1. **The fee belongs to the v1 product.** Add it to the v1 product's fees, so the catalog derives the
   price code for it, and republish. The projected revision then carries the fee's derived code, and
   billing charges it under the v1 fee id. This is the right choice when v1 clients must also see the fee.
2. **The product is defined in the v2 kernel only.** Publish a revision **without** the embedded v1
   document, with every fee tagged `role: FEE`. With no v1 document there is no earlier fee id to
   collide with, so billing uses the component code as the fee id and charges it.

Then re-pin the affected accounts to the new revision (the pin never moves on its own, ADR-0341 D1).
Fees for cycles already assessed under the old revision were not charged. Whether to bill them
retroactively is a business decision, recorded in the change ticket; do not re-run past cycles from
this runbook.

The alert clears once 6h pass with no new drop.

## What not to do

Do not edit a published revision's content or code to make the codes match: published revisions are
immutable (the catalog database refuses it). Do not rename the component code on a new revision to a
code that a *different* v1 fee derives: billing would charge it under that other fee's id.
