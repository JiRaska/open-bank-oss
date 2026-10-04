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
 * Proves the decision-evidence purge RUNS when the scheduler dispatches it, against real Postgres.
 *
 * It drives the cron instead of calling `purge()`: a direct call supplies the Vert.x context the
 * real scheduler does not, so it would pass against a non-`suspend` `runBlocking` body that can
 * never work as a cron (#2148, #2187).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(DecisionEvidencePurgeSchedulerIT.FastPurgeProfile::class)
class DecisionEvidencePurgeSchedulerIT {

    /** Literals only: a QuarkusTestProfile loads in a different classloader from the test. */
    class FastPurgeProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.sca.decision-purge.enabled" to "true",
            "openbank.sca.decision-purge.cron" to "*/2 * * * * ?",
            "openbank.sca.decision-retention-days" to "30",
            "openbank.sca.decision-purge.batch-size" to "1",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `a scheduler-dispatched purge deletes evidence past retention and keeps fresh evidence`() {
        val expiredA = seed(decidedDaysAgo = 45)
        val expiredB = seed(decidedDaysAgo = 31)
        val fresh = seed(decidedDaysAgo = 29)

        // batch-size 1 forces the multi-batch path inside a single scheduled run.
        val purged = await { !exists(expiredA) && !exists(expiredB) }
        assertThat(purged)
            .describedAs(
                "a scheduler-dispatched purge must delete decision evidence older than the retention " +
                    "period — never doing so means the tick never ran or threw off the Vert.x context",
            )
            .isTrue()

        Thread.sleep(SETTLE_MILLIS)
        assertThat(exists(fresh)).describedAs("evidence inside retention must survive the purge").isTrue()
        assertThat(challengeExists(fresh)).describedAs("the purge deletes evidence only, never the challenge").isTrue()
    }

    private fun seed(decidedDaysAgo: Int): UUID {
        val challenge = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO sca_challenges " +
                    "(id, party_id, purpose, method, status, expires_at, created_at, attempt_count, max_attempts) " +
                    "VALUES (?, ?, 'LOGIN', 'PUSH_NOTIFICATION', 'COMPLETED', now() - (? * interval '1 day') + " +
                    "interval '5 minutes', now() - (? * interval '1 day'), 0, 3)",
            ).use { query ->
                query.setObject(1, challenge)
                query.setObject(2, UUID.randomUUID())
                query.setInt(3, decidedDaysAgo)
                query.setInt(4, decidedDaysAgo)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
            connection.prepareStatement(
                "INSERT INTO sca_device_decisions (challenge_id, credential_id, decision, signature_b64, " +
                    "signed_payload_b64, decided_at, expires_at, challenge_version) VALUES (?, ?, 'APPROVED', " +
                    "'c2ln', 'cGF5bG9hZA==', now() - (? * interval '1 day'), " +
                    "now() - (? * interval '1 day') + interval '5 minutes', 0)",
            ).use { query ->
                query.setObject(1, challenge)
                query.setString(2, "purge-credential-$challenge")
                query.setInt(3, decidedDaysAgo)
                query.setInt(4, decidedDaysAgo)
                assertThat(query.executeUpdate()).isEqualTo(1)
            }
        }
        return challenge
    }

    private fun exists(challenge: UUID): Boolean = count("sca_device_decisions", "challenge_id", challenge) == 1L

    private fun challengeExists(challenge: UUID): Boolean = count("sca_challenges", "id", challenge) == 1L

    private fun count(table: String, column: String, id: UUID): Long = dataSource.connection.use { connection ->
        val query = connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?")
        query.setObject(1, id)
        val rows = query.executeQuery()
        rows.next()
        rows.getLong(1).also { query.close() }
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
