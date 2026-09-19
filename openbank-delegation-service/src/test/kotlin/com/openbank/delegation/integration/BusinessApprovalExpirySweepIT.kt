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
import java.util.UUID

/**
 * The ADR-0312 expiry sweep, driven by the REAL scheduler (CLAUDE.md: a direct call supplies the
 * Vert.x context a plain @Scheduled method lacks, so it proves nothing). The cron shrinks to every
 * two seconds; rows are seeded over JDBC because a reactive repository cannot run on a bare test
 * thread for the same reason.
 */
@QuarkusTest
@QuarkusTestResource(BusinessApprovalExpirySweepIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(BusinessApprovalExpirySweepIT.FastExpiryProfile::class)
class BusinessApprovalExpirySweepIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
            "approval-events-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    /** Dispatch off, so the enqueued APPROVAL_EXPIRED row is still in the outbox to be read. */
    class FastExpiryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.delegation.business-signing.expiry-cron" to "*/2 * * * * ?",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    // Literals: a QuarkusTestProfile loads in a different classloader (CLAUDE.md).
    private val overdue = UUID.fromString("0198f2aa-0000-7000-8000-00000000a301")
    private val overdueApproved = UUID.fromString("0198f2aa-0000-7000-8000-00000000a302")
    private val live = UUID.fromString("0198f2aa-0000-7000-8000-00000000a303")
    private val released = UUID.fromString("0198f2aa-0000-7000-8000-00000000a304")
    private val unsigned = UUID.fromString("0198f2aa-0000-7000-8000-00000000a305")

    private fun connect() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private fun seed(id: UUID, status: String, expiresSql: String, claimed: Boolean = false) = connect().use { c ->
        c.prepareStatement(
            """
            insert into approval_requests
              (id, entity_party_id, kind, payload, payload_sha256, policy_version, required_signatures,
               eligible_signer_ids, initiator_party_id, status, expires_at, created_at, updated_at, claim_token)
            values (?, ?, 'PAYMENT', '{}', ?, 0, 2, '[]', ?, ?, $expiresSql, now(), now(), ?)
            on conflict (id) do nothing
            """.trimIndent(),
        ).use {
            it.setObject(1, id)
            it.setObject(2, UUID.randomUUID())
            it.setString(3, "a".repeat(SHA_LENGTH))
            it.setObject(4, UUID.randomUUID())
            it.setString(5, status)
            if (claimed) it.setObject(6, UUID.randomUUID()) else it.setNull(6, java.sql.Types.OTHER)
            it.executeUpdate()
        }
    }

    private fun status(id: UUID): String? = connect().use { c ->
        c.prepareStatement("select status from approval_requests where id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private fun expiredEvents(id: UUID): Int = connect().use { c ->
        c.prepareStatement("select count(*) from delegation_outbox where aggregate_id = ? and event_type = 'APPROVAL_EXPIRED'").use {
            it.setObject(1, id)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return ready()
    }

    @Test
    fun `the real scheduler expires overdue pending and unclaimed approved requests, and nothing else`() {
        seed(overdue, "PENDING", "now() - interval '1 minute'")
        seed(overdueApproved, "APPROVED", "now() - interval '1 minute'")
        seed(live, "PENDING", "now() + interval '1 day'")
        seed(released, "RELEASED", "now() - interval '1 minute'", claimed = true)
        seed(unsigned, "AWAITING_INITIATOR", "now() - interval '1 minute'")

        assertThat(await { status(overdue) == "EXPIRED" && status(overdueApproved) == "EXPIRED" })
            .describedAs("a scheduler-dispatched sweep must expire both; never doing so means it lost the Vert.x context")
            .isTrue()
        assertThat(expiredEvents(overdue)).isEqualTo(1)
        assertThat(expiredEvents(overdueApproved)).isEqualTo(1)
        assertThat(await { status(unsigned) == "EXPIRED" }).describedAs("an unsigned request expires too").isTrue()
        assertThat(expiredEvents(unsigned)).describedAs("…silently: it was never announced").isZero()

        Thread.sleep(SETTLE_MILLIS)
        assertThat(status(live)).isEqualTo("PENDING")
        assertThat(status(released)).describedAs("a released payment already executed; expiry never rewrites it").isEqualTo("RELEASED")
        assertThat(expiredEvents(overdue)).describedAs("idempotent across ticks").isEqualTo(1)
    }

    private companion object {
        const val SHA_LENGTH = 64
        const val POLL_MILLIS = 250L
        const val SETTLE_MILLIS = 4_000L
        const val BUDGET_NANOS = 45_000_000_000L
    }
}
