// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.infrastructure.persistence.repository.BillingAssessmentRepositoryImpl
import com.openbank.billing.infrastructure.scheduler.BillingCycleScheduler
import com.openbank.billing.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate

/**
 * Regression coverage for the #2148 class of dead-scheduler bug, found in billing by the #2187
 * fleet sweep: the monthly billing cycle sweep could never touch the database.
 *
 * [BillingCycleScheduler.sweep] was a plain `fun sweep(): Unit = runBlocking { runSweep() }`.
 * Quarkus invokes a plain `@Scheduled` method on a bare `executor-thread`, which carries no Vert.x
 * context, so the first reactive Panache query inside (`BillingAssessmentRepositoryImpl.findExisting`,
 * via `sf.withSession`) threw `HR000068: This method should exclusively be invoked from a Vert.x
 * EventLoop thread` and the whole sweep aborted with **zero** assessment rows written.
 *
 * **Why this test drives the cron and not `runSweep()` directly.** The defect is in *how the
 * framework invokes the method*, so calling `sweep()`/`runSweep()` from the test supplies a context
 * the scheduler does not, and proves nothing — which is exactly why the mocked-collaborator
 * `BillingCycleSchedulerTest` never saw it. The profile below shrinks the cron to every two seconds
 * and re-enables the scheduler (`%test` turns it off fleet-wide, ADR-0050), so a genuinely
 * scheduler-dispatched sweep runs against a real Postgres. Reverting the fix to `runBlocking` makes
 * this time out with no assessment row and HR000068 in the log.
 *
 * The assertion is scoped to this test's own account id, so nothing another IT leaves behind in the
 * shared Postgres can affect the result. There is no account-service in this profile, so the
 * resolved assessment is a fail-closed `skipped` row (same as [BillingCycleServiceIT]) — that a row
 * exists at all is the point: it can only have been written from a Vert.x context.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(BillingCycleSweepIT.FastSweepProfile::class)
class BillingCycleSweepIT {

    /**
     * Fires the cycle sweep every two seconds instead of 03:00 on the 1st, over a single
     * operator-configured account (rule 1 of the [BillingCycleScheduler] KDoc — the CSV override, so
     * the fleet-wide discovery sweep stays off and no account-service read is attempted). The outbox
     * dispatcher stays inert: `quarkus.scheduler.enabled` also arms the outbox tick, and a
     * dispatching outbox would publish against a Kafka that is not there.
     */
    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.billing.scheduler.enabled" to "true",
            "openbank.billing.scheduler.cron" to "*/2 * * * * ?",
            "openbank.billing.scheduler.account-ids" to SWEEP_ACCOUNT_ID,
            "openbank.billing.scheduler.currency" to SWEEP_CURRENCY,
            "openbank.billing.scheduler.discovery-enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var assessmentRepository: BillingAssessmentRepositoryImpl

    @Inject
    lateinit var clock: Clock

    // Run a reactive suspend body on a fresh Vert.x duplicated context and block for its result,
    // so Panache/Mutiny finds the context it requires (mirrors BillingCycleServiceIT).
    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    /** Polls until the sweep has written the account's assessment row, or the budget runs out. */
    private fun awaitAssessment(cycleId: String): Boolean {
        val deadline = System.nanoTime() + SWEEP_BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            val found = onVertxContext {
                assessmentRepository.findExisting(cycleId, SWEEP_ACCOUNT_ID, SWEEP_CURRENCY)
            }
            if (found != null) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return onVertxContext {
            assessmentRepository.findExisting(cycleId, SWEEP_ACCOUNT_ID, SWEEP_CURRENCY)
        } != null
    }

    @Test
    fun `the scheduled sweep assesses its configured account against a real database`() {
        val cycleId = BillingCycleScheduler.cycleIdFor(LocalDate.now(clock))

        assertThat(awaitAssessment(cycleId))
            .describedAs(
                "a scheduler-dispatched sweep must persist a billing_cycle_assessment row for " +
                    "$SWEEP_ACCOUNT_ID in cycle $cycleId — no row means the sweep threw HR000068 " +
                    "off the Vert.x context (#2148 / #2187)",
            )
            .isTrue()

        val assessment = onVertxContext {
            assessmentRepository.findExisting(cycleId, SWEEP_ACCOUNT_ID, SWEEP_CURRENCY)
        }
        assertThat(assessment).isNotNull
        assertThat(assessment!!.cycleId).isEqualTo(cycleId)
        assertThat(assessment.accountId).isEqualTo(SWEEP_ACCOUNT_ID)
        assertThat(assessment.skipped)
            .describedAs("no account-service in this profile, so the assessment fails closed as skipped")
            .isTrue()
        assertThat(assessment.skipReason).isEqualTo("ACCOUNT_CONTEXT_UNRESOLVED")
    }

    private companion object {
        const val SWEEP_ACCOUNT_ID = "billing-sweep-it-account"
        const val SWEEP_CURRENCY = "CZK"

        /** Generous vs the 2s cron so a slow CI runner cannot flake the wait. */
        const val SWEEP_BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
