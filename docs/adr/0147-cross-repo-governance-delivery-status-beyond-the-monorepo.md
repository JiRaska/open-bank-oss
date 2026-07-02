# ADR-0147 — Cross-repo governance — delivery status beyond the monorepo

Date: 2026-07-02
Decision-Status: Accepted   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Shipped    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): jiri.raska

**Delivery note (2026-07-02):** `docs/adr/known-repos.txt` (allowlist,
`openbank-app` listed), `TEMPLATE.md` documents the optional
`Delivery-Repos:` field, `gen-index.sh` renders a Repos column,
`check-adr-registry.sh` validates any declared value against the
allowlist. Applied to the concrete motivating case: ADR-0095 now carries
`Delivery-Repos: openbank-app` and its Delivery-Status corrected to
Partial (verified, not inferred). ADR-0064 carries the pointer only —
its own Delivery-Status is left unverified rather than guessed.

## Context

Not every ADR's delivery lives in this monorepo. ADR-0095 (QRlessPay BLE
proximity payments) is explicit about this: the BLE peripheral/central
client work ships in `openbank-app` (a separate Apache-2.0 repo, referenced
by its own ADR numbering — "ADR-0087 in `openbank-app`" — independent of
this repo's sequence), with only the wire-format spec and threat model
living here. `openbank-app` also carries the customer-facing Kotlin
Multiplatform client for ADR-0064/0066/0073, and ADR-0031 D8 anticipated a
possible separate agent-runtime repo.

Nothing in `catalog.json`, the governance manifest, or `gen-index.sh`
currently records that an ADR's delivery status is partly or wholly
external. A reviewer — human or an AI agent doing exactly the kind of
audit this ADR sweep came out of — reading only this repo will conclude an
externally-delivered feature is "paper only," which is a false negative:
it happened to this review for ADR-0095 specifically. As the platform grows
more satellite repos (customer app, and potentially the agent-runtime
carve-out from ADR-0136), this failure mode gets more common, not less.

## Decision

We will add an explicit, minimal cross-repo delivery field rather than
build a cross-repo CI system: any ADR whose delivery is partly or wholly
outside this monorepo declares `Delivery-Repos: <repo-name>[, <repo-name>]`
in its front-matter (alongside the existing `Decision-Status` /
`Delivery-Status` two-axis header), and its Delivery note names the specific
artifact in that repo (a tag, a release, or that repo's own ADR id, the way
ADR-0095 already informally does). `gen-index.sh` is extended to render this
field in the index table so `Delivery-Status: Partial` next to an empty
`Delivery-Repos` reads differently from `Delivery-Status: Partial` with
`Delivery-Repos: openbank-app` — the latter is a pointer, not a gap.
`.github/scripts/check-adr-registry.sh` gets one new check: a declared
`Delivery-Repos` value must be a name present in a small
`docs/adr/known-repos.txt` allowlist (to catch a typo'd repo name, not to
validate the remote repo's actual state, which is out of scope).

## Alternatives considered

- **Merge `openbank-app` (and any future satellite repo) into this
  monorepo.** Rejected — different release cadence (mobile app-store
  review cycles vs. this repo's continuous per-service release-please),
  different toolchain (Kotlin Multiplatform/Compose vs. Quarkus/Next.js),
  and different secret surface (App Store credentials). Merging would trade
  a documentation gap for a much larger operational one.
- **Build automated cross-repo status sync (e.g. a scheduled job that reads
  `openbank-app`'s tags/releases and writes them back into this repo's
  `catalog.json`).** Rejected for now — this is the eventual correct
  answer once there are enough satellite repos to justify the automation
  cost, but for one satellite repo today it is solving a problem that does
  not yet exist at scale. The frontmatter-field version is cheap and can be
  upgraded to automation later without a breaking change to the schema.
- **Do nothing; rely on ADR authors remembering to mention the other repo
  in prose, as ADR-0095 already does.** Rejected — it worked for ADR-0095
  because its author happened to write it clearly; it is not a system,
  it is one author's diligence, and it just produced a wrong finding in an
  external review that had no way to discover the prose convention.

## Consequences

**Positive**
- Removes a specific, demonstrated false-negative failure mode ("this ADR
  looks unimplemented") for any audit, review, or AI agent reading only
  this repo.
- Cheap: one frontmatter field, one allowlist file, one small addition to
  an existing script — no new infrastructure, consistent with not
  over-building before the problem is large.
- Extends naturally if `openbank-app` or a future agent-runtime repo grows
  its own governance-as-code (`rules.yaml`-equivalent) — this ADR's field
  is a pointer other tooling can key off later.

**Negative**
- `Delivery-Repos` truthfulness is not verified against the target repo
  automatically (see rejected alternative #2) — it is only checked for
  "is this a known repo name," not "is the claimed artifact actually
  there." A stale or wrong pointer is possible until automation is added.
- Requires a one-time sweep of existing ADRs that already have undeclared
  external delivery (starting with ADR-0095) to backfill the new field.

**Neutral**
- Does not change `Decision-Status`/`Delivery-Status` semantics for
  monorepo-only ADRs, which remain the overwhelming majority.

## Compliance impact

- PCI DSS: not applicable directly.
- DORA: Art. 8 (complete and accurate ICT asset inventory — the client app
  is an ICT asset whose delivery state should be discoverable from the
  same governance surface as everything else).
- GDPR: not applicable directly.
- PSD2: not applicable directly.
- CNB: not applicable directly.

## References

- ADR-0095 (QRlessPay BLE proximity SPAYD payments) — the concrete example
  that motivated this ADR; its client delivery in `openbank-app` is
  correctly documented in prose but invisible to tooling.
- ADR-0064 (customer app Kotlin Multiplatform) — the other major
  `openbank-app` delivery surface.
- ADR-0136 (agent services AGPL open-core) — D8's contemplated future
  agent-runtime repo, the next likely case this ADR needs to cover.
- ADR-0071 (governance manifest as derived data) — the principle this ADR
  extends across a repo boundary.
- `docs/adr/gen-index.sh`, `.github/scripts/check-adr-registry.sh`.
