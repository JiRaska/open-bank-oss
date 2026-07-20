---
date: YYYY-MM-DD
decision-status: proposed
delivery-status: planned
authors: [<name>]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [<tag>]
summary: "One or two sentences: what is decided and why. Max 240 chars."
---

# ADR-NNNN — <Short noun phrase>

<!--
Front-matter fields, enums and rules: docs/adr/SCHEMA.md. The number and title are
NOT repeated in the front-matter — they come from the filename and this H1.

`docs/adr/new.sh "Title"` writes the block above for you with a collision-free
number. Before pushing:
    bash docs/adr/gen-index.sh && bash .github/scripts/check-adr-registry.sh

Write `summary` last, once you know what you actually decided. It is the line that
represents this ADR in DIGEST.md, which is what people and agents read instead of
the ~225k-word fleet — so it has to state the DECISION, not the topic.
-->

## Context

What forces are at play (technical, business, regulatory)? What is the
problem we are solving? Why now?

## Decision

What is the change we are proposing or have agreed to?

State the decision clearly in active voice: "We will ..."

## Alternatives considered

- **Option A** — short description. Pros / cons. Why rejected.
- **Option B** — short description. Pros / cons. Why rejected.

<!-- Required (enforced). Reconstruct, never invent: if no alternative was genuinely
     considered, say exactly that instead of manufacturing a plausible rejected option. -->

## Consequences

**Positive**
- ...

**Negative**
- ...

**Neutral**
- ...

## Compliance impact

<!-- Required (enforced). Do NOT write an article, clause or requirement number unless
     that exact citation appears in this ADR's own text — auditors read these rows as
     claims about the platform. Otherwise name the engagement in plain words, or write
     "not applicable — <specific reason>". For most internal engineering decisions,
     four or five rows are honestly "not applicable". That is the right answer. -->

- PCI DSS: <req refs> / not applicable
- DORA:    <req refs> / not applicable
- GDPR:    <req refs> / not applicable
- PSD2:    <req refs> / not applicable
- CNB:     <req refs> / not applicable

## References

- ...
