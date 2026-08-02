// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.application.usecase.FeeAssessmentService
import com.openbank.billing.application.usecase.FeeReversalService
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.infrastructure.rest.BillingResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal

/**
 * Query-parameter validation on the fee endpoints (#3038).
 *
 * The endpoints used to check only `isNullOrBlank`, so any non-blank garbage travelled unchecked
 * into `persistWithPostingIntent` and died at the schema — `currency CHAR(3) NOT NULL`,
 * `cycle_id`/`account_id VARCHAR(64)` — as an unmapped Postgres error, i.e. a 500 for a client
 * error. The authenticated fuzz run found it on `POST /fees/post` with `currency=ISO-2022-CN`.
 *
 * The assertion that matters in each case is the pair: **400, and the use case was never reached**.
 * A 400 alone would still pass if validation ran after the write.
 */
class BillingResourceValidationTest {

    private val assessmentService = mockk<FeeAssessmentService>()
    private val cycleService = mockk<BillingCycleService>()

    private fun resource(): BillingResource {
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns Principal { "operator-1" }
        return BillingResource(assessmentService, cycleService, mockk<FeeReversalService>())
            .also { it.identity = identity }
    }

    private fun assessment(currency: String) =
        BillingAssessment("c1", "acc1", currency, skipped = false, skipReason = null, assessedFees = emptyList())

    /** The exact input from the fuzz run. 11 characters into a `CHAR(3)` column. */
    @Test
    fun `the fuzz input that produced a 500 is now a 400, and never reaches the use case`(): Unit = runBlocking {
        val response = resource().post("Windows-1253", "acc1", "ISO-2022-CN")

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity.toString()).contains("ISO-2022-CN")
        coVerify(exactly = 0) { cycleService.assessAndPost(any(), any(), any()) }
    }

    @Test
    fun `a three-letter code that is not ISO 4217 is rejected too`(): Unit = runBlocking {
        val response = resource().post("c1", "acc1", "ZZZ")

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { cycleService.assessAndPost(any(), any(), any()) }
    }

    @Test
    fun `an over-long cycleId is rejected before it reaches VARCHAR(64)`(): Unit = runBlocking {
        val response = resource().post("c".repeat(65), "acc1", "CZK")

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { cycleService.assessAndPost(any(), any(), any()) }
    }

    @Test
    fun `an over-long accountId is rejected before it reaches VARCHAR(64)`(): Unit = runBlocking {
        val response = resource().post("c1", "a".repeat(65), "CZK")

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { cycleService.assessAndPost(any(), any(), any()) }
    }

    /** Exactly 64 is the column width, so it must pass — an off-by-one here would reject valid ids. */
    @Test
    fun `an id of exactly the column width is accepted`(): Unit = runBlocking {
        val accountId = "a".repeat(64)
        coEvery { cycleService.assessAndPost("c1", accountId, "CZK") } returns assessment("CZK")

        assertThat(resource().post("c1", accountId, "CZK").status).isEqualTo(200)
    }

    /**
     * Case normalisation is deliberate: `czk` and `CZK` would otherwise be two distinct values in
     * both the `CHAR(3)` column and the `(cycleId, accountId, currency)` idempotency key, so the
     * same fee could be assessed twice under two spellings of one currency.
     */
    @Test
    fun `a lower-case currency is normalised before it reaches the use case`(): Unit = runBlocking {
        coEvery { cycleService.assessAndPost("c1", "acc1", "CZK") } returns assessment("CZK")

        assertThat(resource().post("c1", "acc1", "czk").status).isEqualTo(200)
        coVerify(exactly = 1) { cycleService.assessAndPost("c1", "acc1", "CZK") }
    }

    /** The pre-existing blank check must keep working — this is the one case that was already 400. */
    @Test
    fun `a blank parameter is still a 400`(): Unit = runBlocking {
        assertThat(resource().post("c1", "acc1", "").status).isEqualTo(400)
        assertThat(resource().post("c1", null, "CZK").status).isEqualTo(400)
    }

    /** `/fees/assess` shares the validation, so the dry-run twin must be guarded identically. */
    @Test
    fun `the assess dry-run rejects the same input`(): Unit = runBlocking {
        val response = resource().assess("Windows-1253", "acc1", "ISO-2022-CN")

        assertThat(response.status).isEqualTo(400)
        coVerify(exactly = 0) { assessmentService.assess(any(), any(), any()) }
    }
}
