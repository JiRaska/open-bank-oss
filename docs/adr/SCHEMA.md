# ADR front-matter schema

Every file matching `docs/adr/[0-9]*.md` starts with a YAML front-matter block.
`.github/scripts/check-adr-registry.sh` enforces this schema on every PR; both
`gen-index.sh` and the validator parse it with the one shared parser in
[`lib-frontmatter.sh`](lib-frontmatter.sh).

## Why this exists

Before this schema the header was prose, and three conventions coexisted across the
fleet (`Status:`, `**Status:**`, `| Status | … |`, plus the two-axis
`Decision-Status:`/`Delivery-Status:`). The index generator carried a fallback regex per
convention and truncated the result to 40 characters, because a status field like

    Status: Accepted (2026-06-14 — decision implemented: `openbank-libs/.../flags`

is a paragraph, not a value. Derived data cannot be trusted when the source is prose.
One machine-readable block, one parser, one validator.

## The block

```yaml
---
date: 2026-07-20
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, sca]
summary: "One or two sentences: what was decided and why. Max 240 chars."
---
```

**The block carries only what cannot be derived.** The ADR *number* comes from the
filename and the *title* from the H1 — duplicating either in front-matter would create a
second source of truth that can drift, which is the defect class this whole registry
exists to prevent. That is also why `README.md`, `index.json` and `DIGEST.md` are
generated, never hand-edited.

## Fields

| Field | Required | Type | Notes |
|---|---|---|---|
| `date` | yes | `YYYY-MM-DD` | Date the decision was made. Immutable once accepted. |
| `decision-status` | yes | enum | `proposed` \| `accepted` \| `superseded` \| `deprecated` \| `rejected` |
| `delivery-status` | yes | enum | `planned` \| `partial` \| `shipped` \| `n-a` |
| `authors` | yes | list | Free text, at least one entry. |
| `supersedes` | yes | list of `NNNN` | Empty list if none. |
| `superseded-by` | yes | list of `NNNN` | Empty list if none. Non-empty ⇔ `decision-status: superseded`. |
| `delivery-repos` | yes | list | Values must appear in [`known-repos.txt`](known-repos.txt) (ADR-0147). Empty = monorepo-only. |
| `tags` | yes | list, 1–4 | Values must appear in [`tags.txt`](tags.txt). |
| `summary` | yes | quoted string, ≤240 chars | See below. |

### The two status axes

They are independent and both required. `decision-status` is about the *decision*
(has it been agreed, is it still current); `delivery-status` is about the *build*
(has it been implemented). An accepted decision that nobody has built yet is
`accepted` / `planned` — a perfectly valid and honest state. A decision-only ADR that
has no build axis at all is `delivery-status: n-a`.

Nuance in the *superseded* case: the ADR keeps whatever `delivery-status` was true when
it was superseded. A shipped decision that a later ADR replaced is
`superseded` / `shipped`, not `superseded` / `n-a` — the code really did exist.

### `summary` — the field that pays for the rest

The fleet is ~225k words. Nothing and nobody loads it. `summary` is the tier that makes
the registry usable without reading it: `DIGEST.md` is 174 of these lines, roughly 4k
tokens, so a reviewer, an auditor or an AI agent can hold the *whole* decision history
at once and open only the two ADRs that actually matter.

That is worth writing properly:

- State the decision, not the topic. "We route settlement through transaction-service,
  not settlement-service, because the ledger is the golden source" — not "Discusses
  settlement routing."
- Include the *why* when it fits. The what alone is not enough to decide whether to open
  the file.
- Present tense, active voice, no "This ADR …" preamble.
- Hard cap 240 characters, enforced. It is one line of YAML — no block scalars, no
  newlines, so the parser stays a `key: value` reader.

## Syntax restrictions

The parser is intentionally a strict, flat, line-oriented reader — not a YAML engine —
so this gate stays pure bash and runs in seconds on a docs-only PR. Therefore:

- one `key: value` per line, no nesting, no multi-line block scalars (`>`/`|`);
- lists are inline flow sequences: `[a, b]` or `[]`;
- `summary` must be double-quoted; any embedded `"` must be escaped as `\"`;
- the block is delimited by `---` on the very first line and a closing `---`;
- unknown keys are rejected — a typo'd key must not silently become a missing field.

## Creating an ADR

`docs/adr/new.sh "Title"` scaffolds a valid block with a collision-free number.
Before pushing:

```bash
bash docs/adr/gen-index.sh            # regenerates README.md, index.json, DIGEST.md
bash .github/scripts/check-adr-registry.sh
```
