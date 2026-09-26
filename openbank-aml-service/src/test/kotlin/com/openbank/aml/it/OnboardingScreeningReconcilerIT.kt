// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.it

import com.openbank.aml.application.port.out.PartyDirectoryPort
import com.openbank.aml.application.port.out.PartyPage
import com.openbank.aml.application.port.out.PartySummary
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The onboarding screening reconciler, driven by the REAL scheduler against a real Postgres.
 *
 * Why the cron and not a direct call: a direct call supplies the Vert.x context a plain scheduler
 * thread does not, so it would pass against a non-`suspend` `runBlocking` body that aborts every
 * real tick (#2148). The profile shrinks the cron to every two seconds and the test waits for rows
 * the scheduler itself wrote. party-service is the only thing stubbed ([StubPartyDirectory]); the
 * case store, the idempotency key's UNIQUE constraint, the outbox and the auto-clear are all real.
 *
 * Idempotency is measured by effect: the test waits until the stub has served several full ticks
 * AFTER the cases appeared, then counts rows — one per stuck party, never two.
 */
@QuarkusTest
@QuarkusTestResource(OnboardingScreeningReconcilerIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OnboardingScreeningReconcilerIT.FastReconcileProfile::class)
class OnboardingScreeningReconcilerIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("aml-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    class FastReconcileProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // `%test.quarkus.scheduler.enabled` is false for this service; this IT switches it back on.
            "quarkus.scheduler.enabled" to "true",
            "openbank.aml.onboarding-reconcile.enabled" to "true",
            "openbank.aml.onboarding-reconcile.cron" to "*/2 * * * * ?",
            // page-size 2 so the four-party fixture needs two pages: pagination is part of the claim.
            "openbank.aml.onboarding-reconcile.page-size" to "2",
            "openbank.aml.auto-clear" to "true",
            "openbank.aml.party-resolution.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubPartyDirectory::class.java)
    }

    /**
     * party-service's `PENDING_KYC` list. Literal ids (a profile loads in another classloader, so a
     * randomised companion value would differ between the scheduler and the assertion).
     */
    @Alternative
    @ApplicationScoped
    class StubPartyDirectory : PartyDirectoryPort {
        override suspend fun listPendingKyc(page: Int, size: Int): PartyPage {
            calls.incrementAndGet()
            val slice = FIXTURE.drop(page * size).take(size)
            return PartyPage(slice, hasMore = (page + 1) * size < FIXTURE.size)
        }

        companion object {
            val calls = AtomicInteger(0)
        }
    }

    @Inject
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun onboardingCases(partyId: UUID): List<String> = onEventLoop {
        pool.preparedQuery(
            "SELECT status FROM aml_cases WHERE party_id = $1 AND screening_type = 'CUSTOMER_ONBOARDING'",
        ).execute(Tuple.of(partyId)).awaitSuspending().map { it.getString("status") }
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
    fun `a scheduler-dispatched tick opens exactly one cleared onboarding case per stuck party`() {
        val opened = await { onboardingCases(SOLE_TRADER).isNotEmpty() && onboardingCases(INDIVIDUAL).isNotEmpty() }
        assertThat(opened)
            .describedAs("the real cron must open the missing onboarding cases (never = the tick aborted)")
            .isTrue()

        // At least three more full passes (2 pages each) after the cases exist: a non-idempotent
        // reconciler would have opened more rows by now.
        val baseline = StubPartyDirectory.calls.get()
        assertThat(await { StubPartyDirectory.calls.get() >= baseline + REPEAT_CALLS }).isTrue()

        assertThat(onboardingCases(SOLE_TRADER)).describedAs("one case, auto-cleared").containsExactly("CLEARED")
        assertThat(onboardingCases(INDIVIDUAL)).containsExactly("CLEARED")
        assertThat(onboardingCases(KYC_IN_PROGRESS))
            .describedAs("KYC not approved: not this job's to screen")
            .isEmpty()
        assertThat(onboardingCases(TRUST)).describedAs("TRUST is not a screened type").isEmpty()
    }

    private companion object {
        val SOLE_TRADER: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a001")
        val INDIVIDUAL: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a002")
        val KYC_IN_PROGRESS: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a003")
        val TRUST: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a004")

        val FIXTURE = listOf(
            PartySummary(SOLE_TRADER, "SOLE_TRADER", "PENDING_KYC", "APPROVED"),
            PartySummary(KYC_IN_PROGRESS, "COMPANY", "PENDING_KYC", "IN_PROGRESS"),
            PartySummary(TRUST, "TRUST", "PENDING_KYC", "APPROVED"),
            PartySummary(INDIVIDUAL, "INDIVIDUAL", "PENDING_KYC", "APPROVED"),
        )

        const val REPEAT_CALLS = 6
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
