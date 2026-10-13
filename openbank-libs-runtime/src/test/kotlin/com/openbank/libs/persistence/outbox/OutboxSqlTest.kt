// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The statement TEXT of the kernel repository (ADR-0327 D3/D6/D8). The plan gate
 * ([OutboxClaimPlanIT]) proves what Postgres does with the claim; this proves the claim says what
 * the ADR says — the anti-join head rule, the rewritten predicate, `SKIP LOCKED`, and that a
 * table name is the only thing that varies between services.
 */
class OutboxSqlTest {

    private val ledger = OutboxTableShape("ledger_outbox")

    private fun String.squash() = replace(Regex("\\s+"), " ").trim()

    @Test
    fun `claim is the D3 head claim - rewritten predicate, two anti-joins, SKIP LOCKED, RETURNING`() {
        val sql = OutboxSql.claim(ledger).squash()
        assertThat(
            sql,
        ).startsWith(
            "UPDATE ledger_outbox SET status = :dispatching, claimed_at = :now, updated_at = :now WHERE id IN (",
        )
        // Finding 5: status IN (...) first so the partial index applies, then the stale and backoff arms.
        assertThat(sql).contains(
            "o.status IN (:pending, :failed, :dispatching) AND (o.status <> :dispatching OR o.claimed_at < :stale) " +
                "AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= :now)",
        )
        // Head rule: no older unsent row of the same aggregate.
        assertThat(sql).contains("AND (p.created_at, p.id) < (o.created_at, o.id))")
        // Nothing of the aggregate freshly in flight on any replica.
        assertThat(
            sql,
        ).contains("WHERE d.aggregate_id = o.aggregate_id AND d.status = :dispatching AND d.claimed_at >= :stale)")
        assertThat(sql).contains("ORDER BY o.created_at, o.id LIMIT :limit FOR UPDATE SKIP LOCKED ) RETURNING *")
        assertThat(sql).describedAs("statuses are bound, never spliced").doesNotContain("'PENDING'", "'DISPATCHING'")
    }

    @Test
    fun `every statement is rendered for the given table and nothing else`() {
        val fx = OutboxTableShape("fx_outbox")
        listOf(
            OutboxSql.claim(fx), OutboxSql.listProcessable(fx), OutboxSql.markSentBatch(fx), OutboxSql.markFailed(fx),
            OutboxSql.lockForFailure(fx), OutboxSql.countProcessable(fx), OutboxSql.oldestProcessable(fx),
            OutboxSql.purgeSent(fx), OutboxSql.purgeDead(fx),
        ).forEach { sql ->
            assertThat(sql).contains("fx_outbox").doesNotContain("ledger_outbox")
        }
    }

    @Test
    fun `markSent is one statement per batch and clears the error`() {
        assertThat(OutboxSql.markSentBatch(ledger)).isEqualTo(
            "UPDATE ledger_outbox SET status = :sent, sent_at = :now, updated_at = :now, " +
                "attempt_count = attempt_count + 1, last_error = NULL WHERE event_id IN (:ids)",
        )
    }

    @Test
    fun `purges are batched by a LIMIT subselect and scoped to one terminal status each`() {
        assertThat(OutboxSql.purgeSent(ledger)).contains("status = :sent AND sent_at < :cut LIMIT :limit")
        assertThat(OutboxSql.purgeDead(ledger)).contains("status = :dead AND updated_at < :cut LIMIT :limit")
        assertThat(OutboxSql.purgeSent(ledger)).doesNotContain(":dead")
    }

    @Test
    fun `count is the PENDING plus FAILED set every v1 override counts`() {
        assertThat(
            OutboxSql.countProcessable(ledger),
        ).isEqualTo("SELECT count(*) FROM ledger_outbox WHERE status IN (:pending, :failed)")
    }

    @Test
    fun `the incentive outliers are expressible - order column and an extra claim assignment`() {
        val incentive =
            OutboxTableShape(
                "incentive_outbox",
                orderColumn = "occurred_at",
                extraClaimAssignments = "claim_token = gen_random_uuid()",
            )
        val sql = OutboxSql.claim(incentive).squash()
        assertThat(sql).contains("updated_at = :now, claim_token = gen_random_uuid() WHERE id IN (")
        assertThat(sql).contains("(p.occurred_at, p.id) < (o.occurred_at, o.id)")
        assertThat(sql).contains("ORDER BY o.occurred_at, o.id")
        assertThat(OutboxSql.oldestProcessable(incentive)).contains("SELECT o.occurred_at FROM incentive_outbox")
    }

    @Test
    fun `identifiers are validated because they are spliced into statement text`() {
        assertThatThrownBy {
            OutboxTableShape("ledger_outbox; DROP TABLE x")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OutboxTableShape("Ledger") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            OutboxTableShape("ok_outbox", orderColumn = "created_at desc")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OutboxTableShape("ok_outbox", extraClaimAssignments = "x = 1; DELETE FROM ok_outbox") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the sent-at column is a knob for incentive's published_at and is validated like the others`() {
        val incentive = OutboxTableShape("incentive_outbox", orderColumn = "occurred_at", sentAtColumn = "published_at")
        assertThat(OutboxSql.purgeSent(incentive)).isEqualTo(
            "DELETE FROM incentive_outbox WHERE id IN (SELECT id FROM incentive_outbox " +
                "WHERE status = :sent AND published_at < :cut LIMIT :limit)",
        )
        assertThat(OutboxSql.markSentBatch(incentive)).contains("SET status = :sent, published_at = :now,")
        assertThat(OutboxSql.purgeSent(ledger)).contains("sent_at < :cut")
        assertThatThrownBy { OutboxTableShape("x_outbox", sentAtColumn = "sent_at; DROP TABLE x") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
