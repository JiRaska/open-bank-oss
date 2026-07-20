---
date: 2026-06-23
decision-status: superseded
delivery-status: n-a
authors: [Jiří Raška]
supersedes: []
superseded-by: [0110]
delivery-repos: []
tags: [fx, customer-edge, mobile-app]
summary: "Superseded by ADR-0110, which now owns the whole same-account pocket-to-pocket currency exchange feature; this record only covered the customer-edge entry point and app wiring before the two duplicate ADRs were consolidated."
---

# 125. Same-account currency exchange (the app's currency swap)

> **Superseded by [ADR-0110](0110-same-account-fx-exchange.md) (2026-06-28).** This ADR and
> ADR-0110 were written by two different sessions for the **same** feature — a same-account
> pocket-to-pocket currency exchange at `…/pockets/{fromCurrency}/exchange`. This one (originally
> numbered 0110, renumbered to 0125 in #2424 to break a numbering collision) covered the
> **customer-edge entry point** and the **app wiring**; ADR-0110 covered the **account-service ↔
> fx-service execution path**. They have been consolidated into ADR-0110, which now owns the whole
> feature — the edge entry point, the app changes, and the (preferred) ledger-authoritative
> single cross-currency `TRANSFER` settlement, plus the account-service alternative path.
>
> No content is lost: the edge design described here is preserved (reworded and expanded) in
> ADR-0110 §1–§2.
> Existing `ADR-0110` citations in `openbank-customer-edge` (which describe this edge design) are
> correct against the consolidated ADR-0110.

The original decision text now lives in **[ADR-0110](0110-same-account-fx-exchange.md)**. See that
record for the context, decision, consequences, and references.
