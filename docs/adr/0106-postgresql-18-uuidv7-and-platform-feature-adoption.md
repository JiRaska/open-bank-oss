# PostgreSQL 18 — UUIDv7 as the identifier convention and low-cost platform wins

Date: 2026-06-22
Status: Accepted
Delivery-Status: Shipped
Author(s): Jiri Raska

## Context

The whole fleet is now on **PostgreSQL 18** on CloudNativePG (runbook 0003 — 23 CNPG clusters
migrated, operator 1.29.1, backups proven). The migration was a *proactive major* to stay off
the trailing edge (ADR-0079 / ADR-0054). We are currently running 18 as if it were 16: none of
the platform features that motivate being on 18 are switched on. This ADR decides which we adopt,
and — deliberately — how little we commit to up front.

The relevant fact about identifiers today:

- **~20+ tables** declare `id UUID PRIMARY KEY DEFAULT gen_random_uuid()` — random **UUIDv4**.
- **249 Kotlin source files** mint identifiers via `java.util.UUID.randomUUID()` at the
  application seam (entity ids, `event_id`/`aggregate_id` on the outbox, idempotency keys).
- The shared `PanacheOutboxEntity` carries a random `eventId: UUID` on top of a `BIGSERIAL`
  surrogate.

Random UUIDv4 as a primary/indexed key is a known anti-pattern *at scale*: inserts land at random
points in the B-tree, so the hot page set is the whole index rather than its right-hand edge,
causing page splits, poor cache locality, more WAL, and index bloat. **UUIDv7** (RFC 9562) keeps
the UUID type and all its properties (decentralised, non-enumerable, safe to expose, generatable
client-side/offline) while making values time-ordered, so inserts append like a sequence.

**Honest framing — this is a convention decision, not an incident response.** We have **not**
measured index bloat or write-latency pain on our tables, and at current (sandbox/beta) volumes
the UUIDv4 cost is very likely still theoretical. The reason to act now is that the *cost of
establishing the right identifier convention is lowest while volumes are low* — not that anything
is on fire. Any claim of "rising latency" would be unsubstantiated; we explicitly avoid making it.
This shapes the decision: cheap, reversible convention changes now; expensive rewrites only on
measured evidence.

## Decision

We adopt UUIDv7 as the **identifier convention for new identifiers**, plus the zero-cost 18
features, and we explicitly defer everything expensive or unverified.

### Tier 1 — commit now (near-zero cost, no sweep, no migration)

1. **UUIDv7 is the convention for *new* identifiers, going forward.**
   - **DB seam:** new and newly-touched columns default to `DEFAULT uuidv7()` (PG 18 built-in)
     instead of `gen_random_uuid()`, applied per-service via Flyway *as each service is touched
     for other reasons*. No standalone migration sweep.
   - **App seam:** new code that mints ids in the domain/application layer uses a single libs
     helper `com.openbank.libs.util.Ids.newId(): UUID` returning a UUIDv7. The helper is
     framework-free so the domain may call it (ADR-0002). **It must be backed by a vetted
     UUIDv7 generator (or delegate to the DB), not a hand-rolled implementation** — correct
     intra-millisecond monotonicity (RFC 9562 §6.2) is the hard part and the whole point of the
     locality benefit; a naive `java.time` + `SecureRandom` version silently loses it.
   - **No big-bang rewrite.** v4 and v7 are the same 128-bit `uuid` type and coexist in one
     column with no type migration and no FK breakage. Existing UUIDv4 rows stay as they are; new
     rows simply start receiving v7 values.
   - **The `randomUUID()` Detekt fence is a Tier-2 follow-up, not now** — a ban can only land
     after the helper exists and call sites are migrated, otherwise it fails the build across 249
     files. Until then this is a code-review convention, not an enforced rule.

2. **pg_upgrade statistics preservation — fold into runbook 0003.**
   PG 18's `pg_upgrade` preserves optimizer statistics across the major upgrade, removing the
   post-upgrade `ANALYZE` storm. Pure documentation; adopt as the standing expectation for the
   next major.

### Tier 2 — only on evidence (deferred, listed so they are not forgotten)

3. **Measure before any column rewrite.** Before physically rewriting any UUIDv4 column to v7,
   gather evidence on the candidate table (`pg_stat_user_indexes`, index size, page-split / WAL
   signals on transactions, journal_lines, payment instructions, outbox). Rewrite a column only
   if its own numbers justify it, as its own scoped migration — never fleet-wide on principle.

4. **Asynchronous I/O is deferred pending a security decision.** PG 18's `io_uring` AIO backend
   could speed up read-heavy reporting (statements, accounting close, FINREP/COREP — ADR-0096/0097),
   **but it conflicts with our container-hardening baseline**: ADR-0081 mandates
   `seccompProfile: RuntimeDefault`, whose default syscall allowlist blocks the `io_uring_*`
   syscalls. PG 18's default `io_method` is `worker` (already an improvement over 16's synchronous
   I/O) and we stay on it. Enabling `io_uring` would require an explicit seccomp exception + a
   benchmark proving it is worth weakening the baseline — its own decision, not this ADR.

5. **Adopt-when-touched, no ADR needed:** B-tree skip scan (lean on it in new index design;
   re-check for now-redundant single-column indexes during normal hygiene) and `RETURNING OLD/NEW`
   (e.g. outbox / audit-chain ADR-0086 capturing before/after images in one statement).

Temporal primary keys (`WITHOUT OVERLAPS`) and virtual generated columns are out of scope and
get their own ADR if a concrete need appears.

## Alternatives considered

- **Do nothing (stay on random UUIDv4 everywhere).** Zero effort, but we never establish a better
  convention and the move to 18 stays purely about currency. Rejected — Tier 1 is cheap enough to
  be worth it as a forward convention.
- **Big-bang rewrite of all existing UUIDv4 columns to v7.** Maximises theoretical benefit but is
  a high-risk data migration (rewriting PKs and every referencing FK across money-path tables) for
  a benefit we have not measured and for keys read by equality, not range. Rejected — see Tier 2.
- **Switch UUID PKs to `BIGSERIAL`/`bigint identity`.** Best raw locality and smallest key, but
  loses what we rely on: enumerable ids (information leak on external surfaces), a central sequence
  (bad for offline/client-side and outbox generation), and a real FK-graph type migration. We
  already use `BIGSERIAL` deliberately for purely-internal append-only surrogates (outbox `id`);
  UUIDv7 is the right tool for externally-meaningful, app-minted ids. Rejected as a blanket policy.
- **Hand-roll UUIDv7 in libs over `java.time` + `SecureRandom`.** Tempting (no dependency), but
  getting intra-millisecond monotonicity and clock-regression handling right is exactly the hard
  part, and a subtle bug there silently defeats the locality benefit on a money-path classpath.
  Rejected — use a vetted generator or the DB's `uuidv7()`.
- **Turn on every 18 feature now (incl. `io_uring`).** Rejected — `io_uring` collides with the
  ADR-0081 seccomp baseline and needs its own security + benchmark decision; bundling it here
  would smuggle a hardening exception into an identifier ADR.

## Consequences

**Positive**
- A single, correct identifier convention going forward (`Ids.newId()` / `DEFAULT uuidv7()`),
  established while the cost of change is lowest.
- New ids on append-heavy tables get sequence-like locality without giving up UUID semantics;
  ids also carry an embedded creation timestamp useful for ordering/debugging.
- The 18 migration starts paying back operationally (statistics preservation) at zero code cost.

**Negative**
- The benefit is **table-dependent and currently unmeasured**: locality only helps where v7 rows
  dominate the index, so slow-growing tables see little for a long time.
- UUIDv7 leaks a **creation timestamp**. Acceptable (often useful) for internal banking ids, but
  any id where creation time must stay secret must keep a random generator — to be called out in
  the helper's docs and in the eventual Detekt allowlist.
- Mixed v4/v7 in older columns: harmless functionally, but "is this id time-ordered" becomes
  table-dependent until older rows age out.
- The libs helper is a `libs` change (full-fleet rebuild) and, if it pulls a generator dependency,
  a money-path classpath dependency decision — small, but not literally free.

**Neutral**
- No data migration, no FK changes, no API / `openapi.yaml` change — ids stay opaque 128-bit UUIDs.
- `BIGSERIAL` surrogates (outbox `id`, etc.) are unchanged.

## Risks / what we have not measured

- **No baseline.** We have not quantified index bloat or write latency on any table; Tier 1 is
  justified as cheap forward hygiene, **not** as a fix for a measured problem. Tier 2 rewrites are
  explicitly gated on collecting that baseline first.
- **Generator correctness.** A wrong UUIDv7 implementation is worse than UUIDv4 (false sense of
  locality); hence the "vetted generator / DB function only" rule.
- **`io_uring` vs hardening.** Enabling AIO without a seccomp exception would silently fall back or
  fail under ADR-0081; deferred precisely so this is a conscious decision, not a surprise.

## Compliance impact

- PCI DSS: not applicable (no cardholder-data shape change).
- DORA:    neutral — no control change; any future `io_uring`/seccomp exception (Tier 2) is an
  ICT-hardening decision that would be assessed there, not here.
- GDPR:    neutral — identifiers stay opaque; the embedded timestamp is creation metadata, not new
  personal data. Surfaces where timing must not leak retain random ids (noted above).
- PSD2:    not applicable.
- CNB:     not applicable (no change to regulatory reporting content).

## References

- Runbook 0003 — PostgreSQL 16→18 major upgrade (CloudNativePG) — the migration this builds on
  (updated with the statistics-preservation note).
- ADR-0002 — hexagonal architecture (domain has zero framework imports → `Ids` lives in `libs/util`).
- ADR-0079 / ADR-0054 — infra lifecycle and FinOps version gating (why we are on 18).
- ADR-0081 — container-hardening baseline (seccomp `RuntimeDefault` → why `io_uring` is deferred).
- ADR-0086 — payment non-repudiation & audit chain (`RETURNING OLD/NEW` opportunity).
- ADR-0096 / ADR-0097 — statutory accounting close / FINREP-COREP returns (read-path beneficiaries).
- RFC 9562 — Universally Unique IDentifiers (UUIDv7, §6.2 monotonicity).
- PostgreSQL 18 release notes — `uuidv7()`, asynchronous I/O (`io_method`), B-tree skip scan,
  pg_upgrade statistics preservation, `RETURNING OLD/NEW`.
