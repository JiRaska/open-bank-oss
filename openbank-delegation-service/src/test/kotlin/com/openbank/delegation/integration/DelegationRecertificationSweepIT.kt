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
 * Exercises the actual scheduled, reactive Postgres path: a due review must create immutable
 * evidence and its outbox notification in one transaction, while leaving the delegation active.
 * Calling the scheduler method directly would miss the framework's execution-context boundary.
 */
@QuarkusTest
@QuarkusTestResource(DelegationRecertificationSweepIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DelegationRecertificationSweepIT.FastSweepProfile::class)
class DelegationRecertificationSweepIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.delegation.recertification.cron" to "*/2 * * * * ?",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    private val dueGrantId = UUID.fromString("0198f2aa-0000-7000-8000-00000000e501")
    private val unconfiguredGrantId = UUID.fromString("0198f2aa-0000-7000-8000-00000000e502")

    private fun connect() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private fun seedGrant(id: UUID, audience: String?) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                  (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                   recertification_audience, valid_from, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACCOUNT', ?, 'SOLO', ?, NOW() - INTERVAL '10 days', 'ACTIVE',
                        NOW() - INTERVAL '4 months', NOW() - INTERVAL '4 months')
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.setString(5, audience)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, 'ACCOUNT_READ_BALANCES') " +
                    "ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
    }

    private fun count(sql: String, id: UUID): Int = queryOne(sql, id) { it.getInt(1) } ?: 0

    private fun status(id: UUID): String? = queryOne(
        "SELECT status FROM delegation_grants WHERE id = ?",
        id,
    ) { it.getString(1) }

    @Suppress("NestedBlockDepth") // JDBC resource lifetimes are intentionally explicit in this real-DB proof.
    private fun <T> queryOne(sql: String, vararg args: Any, read: (ResultSet) -> T): T? {
        connect().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { results -> return if (results.next()) read(results) else null }
            }
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + AWAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    @Test
    fun `scheduler creates one due cycle and outbox event without suspending access`() {
        seedGrant(dueGrantId, "CORPORATE")

        val materialized = await {
            count("SELECT COUNT(*) FROM delegation_recertification_cycles WHERE delegation_id = ?", dueGrantId) == 1 &&
                count(
                    "SELECT COUNT(*) FROM delegation_outbox " +
                        "WHERE aggregate_id = ? AND event_type = 'DelegationRecertificationDue'",
                    dueGrantId,
                ) == 1
        }

        assertThat(materialized).isTrue()
        assertThat(status(dueGrantId)).isEqualTo("ACTIVE")

        Thread.sleep(SETTLE_MILLIS)
        assertThat(count("SELECT COUNT(*) FROM delegation_recertification_cycles WHERE delegation_id = ?", dueGrantId))
            .isEqualTo(1)
        assertThat(
            count(
                "SELECT COUNT(*) FROM delegation_outbox " +
                    "WHERE aggregate_id = ? AND event_type = 'DelegationRecertificationDue'",
                dueGrantId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `grant without a selected audience is never inferred into a review cycle`() {
        seedGrant(unconfiguredGrantId, null)

        Thread.sleep(SETTLE_MILLIS)

        assertThat(
            count(
                "SELECT COUNT(*) FROM delegation_recertification_cycles WHERE delegation_id = ?",
                unconfiguredGrantId,
            ),
        ).isZero()
        assertThat(status(unconfiguredGrantId)).isEqualTo("ACTIVE")
    }

    private companion object {
        const val POLL_MILLIS = 250L
        const val SETTLE_MILLIS = 5_000L
        const val AWAIT_NANOS = 45_000_000_000L
    }
}
