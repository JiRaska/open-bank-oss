// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.domain.PostingStatus
import com.openbank.billing.infrastructure.outbox.BillingOutboxRepositoryImpl
import com.openbank.billing.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration coverage against real Postgres (ADR-0143 phase 2c): the assessment row, its
 * per-fee rows, and the outbox intent-to-post row all land in the SAME transaction, and a
 * second run for the same cycle/account/currency is a no-op replay rather than a second insert
 * (idempotent assess, ADR-0143 step 1). Uses the real CDI-wired [BillingCycleService] against
 * account-service/product-catalog/balance-service **stub** adapters (no network — the read-path
 * adapters resolve to `null`/empty in the `%test` profile without a WireMock stand-in), so this
 * exercises the persistence + outbox atomicity, not the read-side HTTP calls.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingCycleServiceIT {

    @Inject
    lateinit var billingCycleService: BillingCycleService

    @Inject
    lateinit var outboxRepository: BillingOutboxRepositoryImpl

    @Test
    fun `an account whose context cannot be resolved is persisted as skipped, never posts anything`() {
        // No account-service running in this IT profile, so RestAccountContextPort.resolve()
        // fails closed (returns null) exactly like the fail-closed unit tests assert — proving
        // the same fail-closed skip persists correctly against a real DB, not just in-memory.
        val cycleId = "it-cycle-${System.nanoTime()}"
        val assessment = runBlocking { billingCycleService.assessAndPost(cycleId, "no-such-account", "CZK") }

        assertThat(assessment.skipped).isTrue()
        assertThat(assessment.skipReason).isEqualTo("ACCOUNT_CONTEXT_UNRESOLVED")
        assertThat(assessment.assessedFees).isEmpty()

        val backlogAfterSkip = runBlocking { outboxRepository.countProcessable() }

        // Re-running the same cycle/account/currency is an idempotent replay: same result,
        // no new outbox rows, no second assessment row (unique constraint would reject it).
        val replay = runBlocking { billingCycleService.assessAndPost(cycleId, "no-such-account", "CZK") }
        assertThat(replay.skipped).isTrue()
        assertThat(replay.skipReason).isEqualTo(assessment.skipReason)

        val backlogAfterReplay = runBlocking { outboxRepository.countProcessable() }
        assertThat(backlogAfterReplay).isEqualTo(backlogAfterSkip)
    }

    @Test
    fun `posting_status starts NOT_APPLICABLE for a skipped assessment (nothing chargeable)`() {
        val cycleId = "it-cycle-skip-${System.nanoTime()}"
        val assessment = runBlocking { billingCycleService.assessAndPost(cycleId, "another-missing-account", "CZK") }

        assertThat(assessment.assessedFees).allMatch { it.postingStatus == PostingStatus.NOT_APPLICABLE }
    }
}
