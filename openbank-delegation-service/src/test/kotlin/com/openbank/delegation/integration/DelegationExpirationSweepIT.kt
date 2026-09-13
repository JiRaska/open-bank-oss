// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * Regression coverage for the expiry sweep that had never expired anything.
 *
 * [com.openbank.delegation.infrastructure.DelegationExpirationJob.sweepExpiredGrants] was a PLAIN
 * (non-`suspend`) `@Scheduled` method bridging to Mutiny with `.subscribe().with(…)`. Quarkus
 * invokes such a method on a bare `executor-thread`, which carries **no Vert.x context**, so the
 * first reactive Panache call threw `HR000068` straight into the subscription's failure handler —
 * one ERROR line per hour, no expiry, no `DelegationExpired` event, and every product-service
 * projection left holding an enforcement row for a grant that was over. Same defect as
 * #2148/#2187, in the Mutiny shape `check-no-runblocking-in-scheduled.py` does not match.
 *
 * **Why this drives the real cron.** The defect is in how the framework invokes the method, not in
 * its body: calling `sweepExpiredGrants()` from a test supplies the very context the scheduler does
 * not, so such a test passes against the broken code and proves nothing. This profile shrinks the
 * cron to every two seconds against a real Postgres and waits for a scheduler-dispatched run to
 * leave its mark in the database.
 *
 * The row is seeded with plain JDBC rather than through the repository, because a reactive Panache
 * repository cannot be driven from a bare `@QuarkusTest` thread for exactly the same reason.
 */
@QuarkusTest
@QuarkusTestResource(DelegationExpirationSweepIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DelegationExpirationSweepIT.FastSweepProfile::class)
class DelegationExpirationSweepIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * Outbox dispatch stays OFF so the enqueued `DelegationExpired` row is still `PENDING` when the
     * assertion reads it — the point of the assertion is that the sweep and the outbox write share
     * the transaction, not that the dispatcher works.
     */
    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.delegation.expiration.cron" to "*/2 * * * * ?",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    // Literal ids, not randomized ones: a QuarkusTestProfile loads in a DIFFERENT classloader from
    // the test class, so a companion-object value computed once would be computed twice and the
    // assertion would look for a row the app never saw.
    private val expiredGrantId = UUID.fromString("0198f2aa-0000-7000-8000-00000000e401")
    private val liveGrantId = UUID.fromString("0198f2aa-0000-7000-8000-00000000e402")

    private fun jdbcUrl(): String =
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)

    private fun connect() = DriverManager.getConnection(jdbcUrl(), "openbank", "openbank_secret")

    /**
     * A capability row is not optional decoration: DelegationGrantEntity.toDomain rebuilds the
     * aggregate, whose invariant is at least one capability. Seeding the grant alone made the
     * sweep abort on the mapping — and abort the WHOLE batch, since findExpiredActive maps every
     * row before the per-grant loop begins.
     */
    private fun seedGrant(id: UUID, validToSql: String) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                  (id, grantor_party_id, grantee_party_id, resource_type, resource_id,
                   approval_policy, valid_from, valid_to, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACCOUNT', ?, 'SOLO', NOW() - INTERVAL '10 days', $validToSql,
                        'ACTIVE', NOW(), NOW())
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, ?) " +
                    "ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "ACCOUNT_READ_BALANCES")
                statement.executeUpdate()
            }
        }
    }

    /** One-row read helper; keeps the callers out of four levels of `use { }`. */
    private fun <T> queryOne(sql: String, vararg args: Any, read: (ResultSet) -> T): T? {
        connect().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
                val rs = statement.executeQuery()
                return if (rs.next()) read(rs) else null
            }
        }
    }

    private fun statusOf(id: UUID): String? = queryOne(
        "SELECT status FROM delegation_grants WHERE id = ?",
        id,
    ) { it.getString(1) }

    private fun outboxCount(id: UUID, eventType: String): Int = queryOne(
        "SELECT COUNT(*) FROM delegation_outbox WHERE aggregate_id = ? AND event_type = ?",
        id,
        eventType,
    ) { it.getInt(1) } ?: 0

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    @Test
    fun `a scheduler-dispatched sweep expires a grant past validTo and enqueues the event`() {
        seedGrant(expiredGrantId, "NOW() - INTERVAL '1 day'")

        val expired = await { statusOf(expiredGrantId) == "EXPIRED" }

        assertThat(expired)
            .describedAs(
                "the REAL scheduler must expire a grant past validTo — never expiring one means the " +
                    "sweep threw HR000068 off the Vert.x context before its first query",
            )
            .isTrue()

        assertThat(outboxCount(expiredGrantId, "DelegationExpired"))
            .describedAs(
                "projections must HEAR the expiry, not merely be able to compute it: their " +
                    "enforcement row closes on the event",
            )
            .isEqualTo(1)
    }

    @Test
    fun `a grant still inside its window is left alone`() {
        seedGrant(liveGrantId, "NOW() + INTERVAL '30 days'")

        // Give the sweep several ticks to get it wrong, then assert it did not.
        Thread.sleep(SETTLE_MILLIS)

        assertThat(statusOf(liveGrantId)).isEqualTo("ACTIVE")
        assertThat(outboxCount(liveGrantId, "DelegationExpired")).isZero()
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 250L
        const val SETTLE_MILLIS = 5_000L
        const val BUDGET_NANOS = 45_000_000_000L
    }
}
