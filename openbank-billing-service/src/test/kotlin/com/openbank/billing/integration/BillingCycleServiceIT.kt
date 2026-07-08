// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.domain.PostingStatus
import com.openbank.billing.infrastructure.outbox.BillingOutboxRepositoryImpl
import com.openbank.billing.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration coverage against real Postgres (ADR-0143 phase 2c): the assessment row, its
 * per-fee rows, and the outbox intent-to-post row all land in the SAME transaction, and a
 * second run for the same cycle/account/currency is a no-op replay rather than a second insert
 * (idempotent assess, ADR-0143 step 1). Uses the real CDI-wired [BillingCycleService] against
 * account-service/product-catalog/balance-service **stub** adapters (no network — the read-path
 * adapters resolve to `null`/empty in the `%test` profile without a WireMock stand-in), so this
 * exercises the persistence + outbox atomicity, not the read-side HTTP calls.
 *
 * [BillingCycleService] and [BillingOutboxRepositoryImpl] are reactive
 * (`Panache.withSession`/`withTransaction`), so their suspend calls MUST run on a Vert.x
 * duplicated context — a plain `runBlocking` test thread has none and fails with "No current
 * Vertx context found" (confirmed the hard way: this exact failure surfaced in CI).
 * [onVertxContext] bridges the suspend body onto a Vert.x context via
 * [VertxContextSupport.subscribeAndAwait] and blocks for the result — mirrors
 * `openbank-ledger-service`'s `JournalPartitionMaintainerIT`.
 *
 * Each test declares an explicit `: Unit` return — a Kotlin/JUnit5 footgun is that `fun x() = expr`
 * inferring a non-`Unit` type makes JUnit5 silently SKIP the test.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingCycleServiceIT {

    @Inject
    lateinit var billingCycleService: BillingCycleService

    @Inject
    lateinit var outboxRepository: BillingOutboxRepositoryImpl

    // Run a reactive suspend body on a fresh Vert.x duplicated context and block for its result,
    // so Panache.withSession/withTransaction find the context they require.
    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    @Test
    fun `an account whose context cannot be resolved is persisted as skipped, never posts anything`(): Unit =
        onVertxContext {
            // No account-service running in this IT profile, so RestAccountContextPort.resolve()
            // fails closed (returns null) exactly like the fail-closed unit tests assert — proving
            // the same fail-closed skip persists correctly against a real DB, not just in-memory.
            val cycleId = "it-cycle-${System.nanoTime()}"
            val assessment = billingCycleService.assessAndPost(cycleId, "no-such-account", "CZK")

            assertThat(assessment.skipped).isTrue()
            assertThat(assessment.skipReason).isEqualTo("ACCOUNT_CONTEXT_UNRESOLVED")
            assertThat(assessment.assessedFees).isEmpty()

            val backlogAfterSkip = outboxRepository.countProcessable()

            // Re-running the same cycle/account/currency is an idempotent replay: same result,
            // no new outbox rows, no second assessment row (unique constraint would reject it).
            val replay = billingCycleService.assessAndPost(cycleId, "no-such-account", "CZK")
            assertThat(replay.skipped).isTrue()
            assertThat(replay.skipReason).isEqualTo(assessment.skipReason)

            val backlogAfterReplay = outboxRepository.countProcessable()
            assertThat(backlogAfterReplay).isEqualTo(backlogAfterSkip)
        }

    @Test
    fun `posting_status starts NOT_APPLICABLE for a skipped assessment (nothing chargeable)`(): Unit = onVertxContext {
        val cycleId = "it-cycle-skip-${System.nanoTime()}"
        val assessment = billingCycleService.assessAndPost(cycleId, "another-missing-account", "CZK")

        assertThat(assessment.assessedFees).allMatch { it.postingStatus == PostingStatus.NOT_APPLICABLE }
    }

    @Test
    fun `two concurrent assessAndPost calls for the same key both succeed, no TOCTOU 500`() {
        // Fix-review finding: findExisting-then-persistWithPostingIntent is a check-then-act race.
        // Each call gets its OWN Vert.x duplicated context on its OWN OS thread (a single shared
        // context is single-threaded by design and isn't a realistic concurrency test); a
        // CountDownLatch lines both threads up at the starting gate to maximize the chance of a
        // genuine race on the DB constraint. Exercises
        // BillingAssessmentRepositoryImpl.recoverConcurrentReplay against the real
        // uq_billing_cycle_assessment constraint — neither call may throw, and both must return
        // the same (skipped) result rather than one winning and one 500ing.
        val cycleId = "it-cycle-race-${System.nanoTime()}"
        val accountId = "race-account"
        val startingGate = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)

        fun call(): BillingAssessment = onVertxContext {
            startingGate.countDown()
            startingGate.await(5, TimeUnit.SECONDS)
            billingCycleService.assessAndPost(cycleId, accountId, "CZK")
        }

        try {
            val first = pool.submit<BillingAssessment> { call() }
            val second = pool.submit<BillingAssessment> { call() }
            val results = listOf(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))

            assertThat(results).hasSize(2)
            results.forEach { assertThat(it.skipped).isTrue() }
            assertThat(results[0].cycleId).isEqualTo(results[1].cycleId)
            assertThat(results[0].accountId).isEqualTo(results[1].accountId)
        } finally {
            pool.shutdown()
        }
    }
}
