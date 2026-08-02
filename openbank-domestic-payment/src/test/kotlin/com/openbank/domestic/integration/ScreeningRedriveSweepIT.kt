// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.integration

import com.openbank.domestic.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Drives the REAL #3266 sweep, on the real scheduler, against a real Postgres.
 *
 * Calling `ScreeningRedriveScheduler.sweep()` directly would prove nothing about the thing most
 * likely to break it: a plain `@Scheduled` method runs on a bare executor thread with no Vert.x
 * context, and the first reactive Panache query then throws HR000068 *before* any per-item
 * try/catch — the sweep aborts having done nothing, silently. A direct call supplies the very
 * context the scheduler does not, so it passes against broken code (#2148, five schedulers in this
 * fleet had never run). Only letting Quarkus invoke it can see that.
 *
 * The profile therefore shrinks the interval instead of disabling it, and every override is a
 * LITERAL: a `QuarkusTestProfile` loads in a different classloader from the test class, so a
 * computed value here would hand the scheduler one number and the assertions another.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(ScreeningRedriveSweepIT.FastSweep::class)
class ScreeningRedriveSweepIT {

    class FastSweep : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.domestic.screening-redrive.enabled" to "true",
            "openbank.domestic.screening-redrive.interval" to "2s",
            "openbank.domestic.screening-redrive.initial-delay" to "1s",
            "openbank.domestic.screening-redrive.max-attempts" to "2",
            "openbank.domestic.screening-redrive.min-age-minutes" to "10",
            "openbank.domestic.screening-redrive.batch-limit" to "20",
            "quarkus.scheduler.enabled" to "true",
        )
    }

    @Inject
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun clear() {
        onEventLoop {
            pool.query("DELETE FROM domestic_payment_outbox").execute().awaitSuspending()
            pool.query("DELETE FROM domestic_payments").execute().awaitSuspending()
        }
    }

    /** Insert a RECEIVED payment directly, so the test controls age and attempt count exactly. */
    private fun heldPayment(key: String, ageMinutes: Long, attempts: Int): UUID {
        val id = UUID.randomUUID()
        onEventLoop {
            pool.preparedQuery(
                """
                INSERT INTO domestic_payments
                  (payment_id, idempotency_key, status, debtor_account_id, debtor_account_number,
                   debtor_bank_code, debtor_name, creditor_account_number, creditor_bank_code,
                   creditor_name, amount, currency, priority, end_to_end_id, transfer_scope,
                   created_at, updated_at, redrive_attempts)
                VALUES ($1,$2,'RECEIVED',$3,'1000','0000','Payer','2000','0000','Payee',
                        100.00,'CZK','STANDARD',$4,'INTERNAL_CLIENT',$5,$5,$6)
                """.trimIndent(),
            ).execute(
                Tuple.of(id, key, UUID.randomUUID(), "E2E-$key")
                    .addOffsetDateTime(
                        Instant.now().minus(Duration.ofMinutes(ageMinutes)).atOffset(java.time.ZoneOffset.UTC),
                    )
                    .addInteger(attempts),
            ).awaitSuspending()
        }
        return id
    }

    /** Poll until [check] holds, or fail with the last value — no awaitility dependency here,
     *  because touching build.gradle.kts costs a fleet-wide dependency resolution. */
    private fun awaitAttempts(id: UUID, timeout: Duration, check: (Int) -> Boolean): Int {
        val deadline = System.nanoTime() + timeout.toNanos()
        var last = attemptsOf(id)
        while (System.nanoTime() < deadline) {
            if (check(last)) return last
            Thread.sleep(POLL_MS)
            last = attemptsOf(id)
        }
        return last
    }

    private fun attemptsOf(id: UUID): Int = onEventLoop {
        pool.preparedQuery("SELECT redrive_attempts FROM domestic_payments WHERE payment_id = $1")
            .execute(Tuple.of(id)).awaitSuspending()
            .iterator().next().getInteger("redrive_attempts")
    }

    /**
     * The load-bearing test: the sweep actually runs under the scheduler (no HR000068), and it
     * respects every bound. All three payments are asserted in one run, so an implementation that
     * simply re-drives everything fails on the two that must be left alone.
     */
    @Test
    fun `the real scheduler re-drives an eligible held payment and respects the bounds`() {
        val eligible = heldPayment("held-eligible", ageMinutes = 60, attempts = 0)
        val tooYoung = heldPayment("held-young", ageMinutes = 1, attempts = 0)
        val exhausted = heldPayment("held-exhausted", ageMinutes = 60, attempts = 2)

        assertThat(awaitAttempts(eligible, Duration.ofSeconds(30)) { it >= 1 })
            .describedAs("the sweep must run on a Vert.x context and pick this up")
            .isGreaterThanOrEqualTo(1)

        assertThat(attemptsOf(tooYoung))
            .describedAs("younger than min-age — a payment still mid-flight must never be touched")
            .isZero()
        assertThat(attemptsOf(exhausted))
            .describedAs("at max-attempts — a genuinely held payment must not be re-screened forever")
            .isEqualTo(2)
    }

    /** The counter must be bounded, not merely incremented — otherwise the sweep never gives up. */
    @Test
    fun `re-drives stop once the attempt budget is spent`() {
        val id = heldPayment("held-budget", ageMinutes = 60, attempts = 0)

        assertThat(awaitAttempts(id, Duration.ofSeconds(30)) { it >= 2 }).isEqualTo(2)
        // Past the cap the row must stay put through several further ticks (interval is 2s).
        Thread.sleep(TimeUnit.SECONDS.toMillis(8))
        assertThat(attemptsOf(id))
            .describedAs("max-attempts is 2 in this profile; the sweep must stop, not keep counting")
            .isEqualTo(2)
    }

    private companion object {
        const val POLL_MS = 1000L
    }
}
