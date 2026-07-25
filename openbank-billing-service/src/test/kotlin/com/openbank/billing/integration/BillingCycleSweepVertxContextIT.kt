// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.domain.BillingAssessment
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
 * Regression coverage for #2187 (the fleet sweep of #2148) — the monthly billing cycle sweep could
 * never have assessed anything.
 *
 * [BillingCycleScheduler.sweep] was a plain (non-`suspend`) method whose body was
 * `runBlocking { runSweep() }`. Quarkus invokes such a method on a bare `executor-thread`, which
 * carries **no Vert.x context**, so the first reactive Panache call reached from it
 * (`BillingAssessmentRepositoryImpl.findExisting`, via `sf.withSession`) threw
 * `HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread` and aborted
 * the cycle. It stayed latent only because the sweep is off by default and no-ops without a
 * configured batch — the first environment to switch it on would have found a cycle that silently
 * assessed nothing.
 *
 * **Why this test drives the cron and not `sweep()`/`runSweep()`.** The defect is in *how the
 * framework invokes the method*, not in the method body: a direct call supplies the Vert.x context
 * the real scheduler does not, so `BillingCycleSchedulerTest` (mocked service, direct `runSweep()`)
 * and `BillingCycleServiceIT` (direct `assessAndPost`) both passed against the broken code.
 *
 * The assessment asserted on is the fail-closed `ACCOUNT_CONTEXT_UNRESOLVED` skip that
 * `BillingCycleServiceIT` also relies on: there is no account-service in this profile, so the
 * account context cannot resolve. That is deliberate — what is under test is that the sweep reaches
 * the database and commits a row at all, not what it decides once it gets there.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(BillingCycleSweepVertxContextIT.FastSweepProfile::class)
class BillingCycleSweepVertxContextIT {

    /**
     * Fires the cycle sweep every two seconds instead of at 03:00 on the 1st, and gives it exactly
     * one account to assess (rule 1 of the scheduler's KDoc — an operator-configured CSV; fleet
     * discovery stays off). `quarkus.scheduler.enabled` is `false` for this whole service under
     * `%test` (application.yaml, ADR-0050) — which is precisely why no billing test could ever have
     * caught #2187: with the scheduler off, the only thing any test exercises is a direct call.
     * The outbox dispatcher stays off so its tick cannot interfere with the assertions.
     */
    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.billing.scheduler.enabled" to "true",
            "openbank.billing.scheduler.cron" to "*/2 * * * * ?",
            "openbank.billing.scheduler.account-ids" to ACCOUNT_ID,
            "openbank.billing.scheduler.currency" to CURRENCY,
            "openbank.billing.scheduler.discovery-enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var assessments: BillingAssessmentRepository

    @Inject
    lateinit var clock: Clock

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun assessment(): BillingAssessment? = onVertxContext {
        assessments.findExisting(BillingCycleScheduler.cycleIdFor(LocalDate.now(clock)), ACCOUNT_ID, CURRENCY)
    }

    @Test
    fun `the scheduled cycle sweep assesses its configured account`() {
        val deadline = System.nanoTime() + BUDGET_NANOS
        var persisted = assessment()
        while (persisted == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            persisted = assessment()
        }

        assertThat(persisted)
            .describedAs(
                "a scheduler-dispatched sweep must persist an assessment for its configured " +
                    "account — nothing persisted means the sweep threw HR000068 off the Vert.x " +
                    "context on its first query (#2187)",
            )
            .isNotNull
        assertThat(persisted!!.accountId).isEqualTo(ACCOUNT_ID)
    }

    private companion object {
        /**
         * A fixed literal, deliberately not a per-run random id: Quarkus loads a
         * [QuarkusTestProfile] in the augmentation classloader and the test class in the runtime
         * one, so this companion is initialized **twice** and a randomized value would give the
         * scheduler one account id and the assertion a different one. Safe because
         * [PostgresRedisTestResource] starts a fresh container per test JVM.
         */
        const val ACCOUNT_ID: String = "sweep-it-2187-account"
        const val CURRENCY = "CZK"

        /** Generous vs the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
