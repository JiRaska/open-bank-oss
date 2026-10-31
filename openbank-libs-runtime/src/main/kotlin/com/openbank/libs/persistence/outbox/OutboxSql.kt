// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

/**
 * The shape of one service's outbox table, as far as the kernel repository needs to know it
 * (ADR-0327 D1). Every statement in [OutboxSql] is rendered from this and nothing else.
 *
 * The defaults describe 37 of the 38 tables in the fleet inventory (ADR-0327 Appendix A). The two
 * knobs exist for the outliers the appendix names, so they can adopt the base without a table
 * rewrite: `openbank-incentive-service` orders by `occurred_at` and stamps a `claim_token` on
 * claim ([orderColumn], [extraClaimAssignments]) and records delivery in `published_at`
 * ([sentAtColumn]); `openbank-security-scanner`'s extra
 * `aggregate_revision` column and the four `candidate`-aliased claims need nothing — an extra
 * column is invisible to these statements, and the alias was only ever a spelling of the same
 * query. Identifiers are validated to a plain SQL-identifier grammar because they are spliced
 * into statement text; parameters never are.
 */
data class OutboxTableShape(
    val table: String,
    /** Column the claim orders on together with `id`; `created_at` everywhere but incentive. */
    val orderColumn: String = "created_at",
    /**
     * Extra `SET` assignments appended to the claim, e.g. `claim_token = gen_random_uuid()`.
     * Rendered verbatim after a comma; empty for the canonical shape. Must not reference
     * parameters other than the claim's own (`:now`, `:stale`, `:limit`, status names).
     */
    val extraClaimAssignments: String = "",
    /** Column recording when the row reached the broker; `sent_at` everywhere but incentive (`published_at`). */
    val sentAtColumn: String = "sent_at",
) {
    init {
        require(IDENTIFIER.matches(table)) { "outbox table name must be a plain SQL identifier, was '$table'" }
        require(IDENTIFIER.matches(orderColumn)) {
            "outbox order column must be a plain SQL identifier, was '$orderColumn'"
        }
        require(IDENTIFIER.matches(sentAtColumn)) {
            "outbox sent-at column must be a plain SQL identifier, was '$sentAtColumn'"
        }
        require(!extraClaimAssignments.contains(';')) {
            "extraClaimAssignments must be a SET fragment, not a statement"
        }
    }

    private companion object {
        val IDENTIFIER = Regex("^[a-z_][a-z0-9_]{0,62}$")
    }
}

/**
 * Renders the kernel's outbox statements (ADR-0327 D3, D6, D8, D10) for one [OutboxTableShape].
 *
 * Kept as a plain object — no Panache, no session — so the statement TEXT is unit-testable
 * (`OutboxSqlTest`) and the plan gate (`OutboxClaimPlanIT`) can `EXPLAIN` the very same string the
 * repository executes rather than a copy that could drift. Parameter names are the contract
 * between here and [AbstractPanacheOutboxRepository]; statuses are bound, never spliced, so the
 * text carries no literal a `check-outbox-claim-sql-ratchet` baseline could mis-key on.
 */
object OutboxSql {
    /** Statuses the partial indexes (D2) cover and every eligibility predicate here filters on. */
    val IN_FLIGHT_STATUSES: List<String> =
        listOf(OutboxStatus.PENDING.name, OutboxStatus.FAILED.name, OutboxStatus.DISPATCHING.name)

    /**
     * The rewritten eligibility predicate (ADR-0327 finding 5): `status IN (…)` first so the
     * partial index applies, then the stale-claim and backoff arms. `:stale` is
     * `now - staleAfter`; `:now` is the claim instant. Alias-free so it can be spliced under any
     * correlation name via [eligible].
     */
    fun eligible(alias: String): String = "$alias.status IN (:pending, :failed, :dispatching)" +
        " AND ($alias.status <> :dispatching OR $alias.claimed_at < :stale)" +
        " AND ($alias.next_attempt_at IS NULL OR $alias.next_attempt_at <= :now)"

    /**
     * D3 claim-by-aggregate-head. Row `o` is claimable only if it is eligible, no OLDER unsent row
     * of its aggregate exists (first anti-join, served by `ix_<t>_outbox_inflight_aggregate`) and
     * nothing of its aggregate is freshly DISPATCHING on any replica (second anti-join). MVCC is
     * the cross-replica guard: a loser of the race for row N still sees N as PENDING (the winner's
     * update is uncommitted) and so cannot qualify N+1. `FOR UPDATE SKIP LOCKED` on the inner
     * select is what lets two replicas claim disjoint heads without blocking.
     */
    fun claim(shape: OutboxTableShape): String {
        val t = shape.table
        val ord = shape.orderColumn
        val extra = if (shape.extraClaimAssignments.isBlank()) "" else ", ${shape.extraClaimAssignments.trim()}"
        return """
            UPDATE $t SET status = :dispatching, claimed_at = :now, updated_at = :now$extra
            WHERE id IN (
              SELECT o.id FROM $t o
              WHERE ${eligible("o")}
                AND NOT EXISTS (SELECT 1 FROM $t p
                                WHERE p.aggregate_id = o.aggregate_id
                                  AND p.status IN (:pending, :failed, :dispatching)
                                  AND (p.$ord, p.id) < (o.$ord, o.id))
                AND NOT EXISTS (SELECT 1 FROM $t d
                                WHERE d.aggregate_id = o.aggregate_id
                                  AND d.status = :dispatching AND d.claimed_at >= :stale)
              ORDER BY o.$ord, o.id
              LIMIT :limit FOR UPDATE SKIP LOCKED
            ) RETURNING *
        """.trimIndent()
    }

    /** Unclaimed peek with the same eligibility predicate — the port's `listProcessable`. */
    fun listProcessable(shape: OutboxTableShape): String =
        "SELECT * FROM ${shape.table} o WHERE ${eligible("o")} ORDER BY o.${shape.orderColumn}, o.id LIMIT :limit"

    /**
     * D6: one statement per batch. `IN (:ids)` rather than `= ANY(:ids)`: Hibernate expands a
     * collection parameter into an IN-list for a native query on every version this fleet runs,
     * whereas binding a `UUID[]` through Hibernate Reactive's Vert.x parameter binder is not
     * something Phase 1 could prove without a booted service. The plan is identical — a unique
     * index probe per id — and the transaction count is what D6 is about.
     */
    fun markSentBatch(shape: OutboxTableShape): String =
        "UPDATE ${shape.table} SET status = :sent, ${shape.sentAtColumn} = :now, updated_at = :now, " +
            "attempt_count = attempt_count + 1, last_error = NULL WHERE event_id IN (:ids)"

    /** First half of `markFailed`: lock the row and read the attempt count the policy needs. */
    fun lockForFailure(shape: OutboxTableShape): String =
        "SELECT attempt_count FROM ${shape.table} WHERE event_id = :eventId FOR UPDATE"

    /** Second half of `markFailed` (D4): the status and schedule are computed in Kotlin from [OutboxBackoff]. */
    fun markFailed(shape: OutboxTableShape): String =
        "UPDATE ${shape.table} SET status = :status, attempt_count = :attempts, last_error = :error, " +
            "updated_at = :now, next_attempt_at = :nextAttemptAt WHERE event_id = :eventId"

    /** O(1) backlog on the partial index: PENDING + FAILED, the same set every v1 override counts. */
    fun countProcessable(shape: OutboxTableShape): String =
        "SELECT count(*) FROM ${shape.table} WHERE status IN (:pending, :failed)"

    /** D10: `created_at` of the oldest row eligible right now, via the `(created_at, id)` partial index. */
    fun oldestProcessable(shape: OutboxTableShape): String =
        "SELECT o.${shape.orderColumn} FROM ${shape.table} o WHERE ${eligible(
            "o",
        )} ORDER BY o.${shape.orderColumn}, o.id LIMIT 1"

    /** D8: batched retention; the caller loops until a short batch. */
    fun purgeSent(shape: OutboxTableShape): String =
        "DELETE FROM ${shape.table} WHERE id IN (SELECT id FROM ${shape.table} WHERE status = :sent AND ${shape.sentAtColumn} < :cut LIMIT :limit)"

    fun purgeDead(shape: OutboxTableShape): String =
        "DELETE FROM ${shape.table} WHERE id IN (SELECT id FROM ${shape.table} WHERE status = :dead AND updated_at < :cut LIMIT :limit)"
}
