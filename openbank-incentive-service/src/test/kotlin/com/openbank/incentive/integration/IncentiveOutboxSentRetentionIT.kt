// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.integration

import com.openbank.incentive.infrastructure.persistence.OutboxEntities
import com.openbank.incentive.it.IncentivePostgresTestResource
import com.openbank.libs.persistence.outbox.SentOutboxRetention
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * SENT-row retention on `incentive_outbox` against real Postgres (ADR-0329, #11902). The table
 * predates the fleet shape — delivery time is `published_at`, not `sent_at` — so this is the one
 * outbox that proves `OutboxTableShape.sentAtColumn` reaches the statement. It also pins the
 * decision that made the purge legal: the evidence of each transition is `incentive_audit_event`,
 * which the purge must leave alone.
 */
@QuarkusTest
@QuarkusTestResource(IncentivePostgresTestResource::class)
@QuarkusTestResource(IncentiveRestContractIT.InMemoryKafkaResource::class)
class IncentiveOutboxSentRetentionIT {

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var outbox: OutboxEntities

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

    private fun count(sql: String): Long = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use {
                it.next()
                it.getLong(1)
            }
        }
    }

    private fun seed(status: String, publishedAt: String?): UUID {
        val id = UUID.randomUUID()
        val published = publishedAt?.let { "TIMESTAMPTZ '$it'" } ?: "NULL"
        exec(
            "INSERT INTO incentive_outbox " +
                "(id, aggregate_id, event_type, payload, occurred_at, published_at, status, updated_at) " +
                "VALUES ('$id', '$id', '$EVENT_TYPE', '{}', $AUG_1, $published, '$status', $AUG_1)",
        )
        return id
    }

    // The Quarkus app and its database are shared with IncentiveRestContractIT, which asserts that
    // incentive_outbox and incentive_audit_event hold the same number of rows: leave none behind.
    @AfterEach
    fun cleanUp() {
        exec("DELETE FROM incentive_outbox WHERE event_type = '$EVENT_TYPE'")
        exec("DELETE FROM incentive_audit_event WHERE event_type = '$EVENT_TYPE'")
    }

    private fun exists(id: UUID) = count("SELECT count(*) FROM incentive_outbox WHERE id = '$id'") == 1L

    @Test
    fun `purges SENT rows by published_at and leaves undelivered rows and the audit trail alone`() {
        val auditId = UUID.randomUUID()
        exec(
            "INSERT INTO incentive_audit_event (id, aggregate_id, event_type, actor, occurred_at, details) " +
                "VALUES ('$auditId', '$auditId', '$EVENT_TYPE', 'it', $AUG_1, '{}')",
        )
        val oldSent = (1..3).map { seed("SENT", "2026-08-01T00:01:00Z") }
        val freshSent = seed("SENT", "2026-09-29T00:00:00Z")
        val oldPending = seed("PENDING", null)
        val oldDead = seed("DEAD", null)
        val retention: SentOutboxRetention = outbox
        val now = Instant.parse("2026-10-03T00:00:00Z")

        var deleted = 0
        do {
            val batch = onVertxContext { retention.purgeSent(Duration.ofDays(7), 2, now) }
            deleted += batch
        } while (batch == 2)

        assertThat(deleted).isGreaterThanOrEqualTo(3)
        assertThat(oldSent.none(::exists)).describedAs("SENT rows published before the window are gone").isTrue()
        assertThat(exists(freshSent)).describedAs("SENT inside the window survives").isTrue()
        assertThat(exists(oldPending)).describedAs("undelivered rows are never purged").isTrue()
        assertThat(exists(oldDead)).describedAs("DEAD rows are the producer-side DLQ").isTrue()
        assertThat(count("SELECT count(*) FROM incentive_audit_event WHERE id = '$auditId'"))
            .describedAs("the audit trail is the evidence and is never touched by outbox retention")
            .isEqualTo(1)
        assertThat(retention.retentionLabel).isEqualTo("incentive")
    }

    private companion object {
        const val EVENT_TYPE = "incentive.retention.test"
        const val AUG_1 = "TIMESTAMPTZ '2026-08-01T00:00:00Z'"
    }
}
