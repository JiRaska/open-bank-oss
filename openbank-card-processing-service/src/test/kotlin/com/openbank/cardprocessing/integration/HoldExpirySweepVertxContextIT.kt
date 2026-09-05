// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.application.port.`in`.AuthorizationCommand
import com.openbank.cardprocessing.application.port.`in`.CardProcessingUseCase
import com.openbank.cardprocessing.application.port.`in`.PresentmentCommand
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.cardprocessing.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the hold-expiry sweep actually runs as a cron.
 *
 * **Why this drives the real scheduler instead of calling `sweep()`.** The failure this guards
 * against is in how Quarkus invokes the method, not in the method body: a plain `@Scheduled` method
 * runs on a bare executor thread with **no Vert.x context**, so the first reactive Panache call
 * throws `HR000068` and the tick aborts having done nothing, silently. A test that calls `sweep()`
 * directly supplies the very context the scheduler does not, so it passes against code that can
 * never work — that is exactly how five schedulers, three of them money-path, were found to have
 * never executed (#2148/#2187).
 *
 * This service disables the scheduler under `%test` so the other integration tests can drive the
 * outbox deterministically; this profile is the one place it is switched back on, with the cron
 * shrunk to every two seconds.
 *
 * The assertion is on a **recording use case**, not on rows: what is under test is whether the
 * scheduler reaches its use case at all. Whether the release logic is right is
 * `AuthorizationLifecycleTest`'s job, where every branch is reachable without a database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(HoldExpirySweepVertxContextIT.FastSweepProfile::class)
class HoldExpirySweepVertxContextIT {

    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.card-processing.hold-sweep-cron" to "*/2 * * * * ?",
            // Off, so a dispatched event cannot be re-consumed in this JVM while the assertion runs.
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> =
            mutableSetOf(RecordingCardProcessingUseCase::class.java)
    }

    /**
     * Records that the scheduler got as far as calling the use case.
     *
     * Literal values only, no randomised id: a `QuarkusTestProfile` loads in a **different
     * classloader** from the test class, so a companion object initialises twice and a randomised
     * value would hand the container one and the assertion another.
     */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    class RecordingCardProcessingUseCase : CardProcessingUseCase {
        override suspend fun authorize(command: AuthorizationCommand): CardAuthorization =
            error("not exercised by this test")

        override suspend fun clear(command: PresentmentCommand): PresentmentOutcome =
            error("not exercised by this test")

        override suspend fun reverse(authorizationId: UUID): PresentmentOutcome =
            error("not exercised by this test")

        override suspend fun findById(id: UUID): CardAuthorization? = null

        override suspend fun findByCard(cardId: UUID, limit: Int): List<CardAuthorization> = emptyList()

        override suspend fun releaseExpiredHolds(limit: Int): Int {
            invocations.incrementAndGet()
            return 0
        }

        companion object {
            val invocations = AtomicInteger(0)
        }
    }

    @Test
    fun `the scheduled hold-expiry sweep reaches its use case`() {
        val reached = await { RecordingCardProcessingUseCase.invocations.get() > 0 }

        assertThat(reached)
            .describedAs(
                "a scheduler-dispatched sweep must reach releaseExpiredHolds — never reaching it " +
                    "means the tick threw HR000068 off the Vert.x context, which leaves every " +
                    "unpresented hold frozen on the customer's money with no error anywhere (#2148)",
            )
            .isTrue()
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
        /** Generous against the 2 s cron so a slow runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
