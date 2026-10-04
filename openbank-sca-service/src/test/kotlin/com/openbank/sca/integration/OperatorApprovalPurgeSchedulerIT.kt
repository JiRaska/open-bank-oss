// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Proves the operator-approval purge RUNS when the scheduler dispatches it, against real Postgres,
 * and deletes every approval whose authorization expired more than the retention period ago — an
 * expired PENDING one included — while keeping rows inside retention and any still-live approval.
 *
 * It drives the cron instead of calling `purge()`: a direct call supplies the Vert.x context the
 * real scheduler does not (#2148, #2187).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OperatorApprovalPurgeSchedulerIT.FastPurgeProfile::class)
class OperatorApprovalPurgeSchedulerIT {

    /** Literals only: a QuarkusTestProfile loads in a different classloader from the test. */
    class FastPurgeProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.sca.approval-purge.enabled" to "true",
            "openbank.sca.approval-purge.cron" to "*/2 * * * * ?",
            "openbank.sca.approval-retention-days" to "30",
            "openbank.sca.approval-purge.batch-size" to "1",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `a scheduler-dispatched purge deletes approvals expired past retention and nothing else`() {
        val approved = seed("APPROVED", expiredDaysAgo = 45)
        val rejected = seed("REJECTED", expiredDaysAgo = 40)
        val executed = seed("EXECUTED", expiredDaysAgo = 31)
        val expiredPendingOld = seed("PENDING", expiredDaysAgo = 4000)
        val freshExecuted = seed("EXECUTED", expiredDaysAgo = 29)
        val expiredPendingRecent = seed("PENDING", expiredDaysAgo = 29)
        val livePending = seed("PENDING", expiredDaysAgo = -1)

        // batch-size 1 forces the multi-batch path inside a single scheduled run.
        val purged = await { listOf(approved, rejected, executed, expiredPendingOld).none(::exists) }
        assertThat(purged)
            .describedAs(
                "a scheduler-dispatched purge must delete terminal approvals past retention — never doing " +
                    "so means the tick never ran or threw off the Vert.x context",
            )
            .isTrue()

        Thread.sleep(SETTLE_MILLIS)
        assertThat(exists(freshExecuted)).describedAs("an approval inside retention must survive").isTrue()
        assertThat(exists(expiredPendingRecent))
            .describedAs("an expired PENDING approval inside retention must survive").isTrue()
        assertThat(exists(livePending)).describedAs("a live PENDING approval is never deleted").isTrue()
    }

    private fun seed(status: String, expiredDaysAgo: Int): UUID {
        val id = UUID.randomUUID()
        val decided = status != "PENDING"
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO sca_operator_approvals (id, action, resource_id, maker_id, status, created_at, " +
                    "expires_at, decided_by, decided_at, claimed_at) VALUES (?, 'device.revoke', 'party', " +
                    "'purge-maker', ?, now() - (? * interval '1 day') - interval '1 day', " +
                    "now() - (? * interval '1 day'), ?, " +
                    "CASE WHEN ? THEN now() - (? * interval '1 day') - interval '1 hour' END, " +
                    "CASE WHEN ? THEN now() - (? * interval '1 day') - interval '1 minute' END)",
            ).use { query ->
                query.setObject(1, id)
                query.setString(2, status)
                query.setInt(3, expiredDaysAgo)
                query.setInt(4, expiredDaysAgo)
                query.setString(5, if (decided) "purge-checker" else null)
                query.setBoolean(6, decided)
                query.setInt(7, expiredDaysAgo)
                query.setBoolean(8, status == "EXECUTED")
                query.setInt(9, expiredDaysAgo)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
        }
        return id
    }

    private fun exists(id: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM sca_operator_approvals WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1) == 1L
            }
        }
    }

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    private companion object {
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val SETTLE_MILLIS = 5_000L
    }
}
