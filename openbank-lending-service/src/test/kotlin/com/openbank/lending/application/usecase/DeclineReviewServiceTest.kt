// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.DeclineReviewRepository
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.origination.OriginationState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DeclineReviewServiceTest {

    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val applications = mockk<LoanApplicationRepository>()
    private val reviews = mockk<DeclineReviewRepository>()
    private val events = mockk<LoanEventEmitter>()
    private val emitted = mutableListOf<LendingOutboxMessage>()
    private val party = UUID.randomUUID()
    private val config = DeclineReviewConfig(WINDOW_DAYS)

    private lateinit var service: DeclineReviewService

    @BeforeEach
    fun setUp() {
        service = DeclineReviewService(applications, reviews, events, clock, config)
        every { applications.withLocked<Any>(any(), any()) } answers {
            applications.findById(firstArg()).flatMap(secondArg<(LoanApplication?) -> Uni<Any>>())
        }
        every { events.emit(any<LendingOutboxMessage>()) } answers {
            emitted += firstArg<LendingOutboxMessage>()
            Uni.createFrom().item(Unit)
        }
        every { reviews.insert(any()) } answers { Uni.createFrom().item(firstArg<DeclineReview>()) }
        every { reviews.loadByApplication(any()) } returns Uni.createFrom().nullItem()
    }

    private fun declined(
        decidedBy: String? = null,
        outcome: String? = "DECLINE",
        status: OriginationState = OriginationState.DECLINED,
        engineAt: OffsetDateTime? = OffsetDateTime.ofInstant(now.minusSeconds(DAY * 2), ZoneOffset.UTC),
        owner: UUID = party,
    ) = LoanApplication(
        partyId = owner,
        requestedAmount = Money.of(BigDecimal("100000"), "CZK"),
        nominalAnnualRate = BigDecimal("0.08"),
        termPeriods = 12,
        firstDueDate = LocalDate.parse("2026-12-01"),
        status = status,
        proposedBy = "edge",
        decidedBy = decidedBy,
        createdAt = OffsetDateTime.ofInstant(now.minusSeconds(DAY * 3), ZoneOffset.UTC),
        decisionOutcome = outcome,
        decisionReasons = "AFFORDABILITY_FAILED:starter-aff-dsti",
        policyVersions = "ELIGIBILITY=1,AFFORDABILITY=1",
        decidedEngineAt = engineAt,
        decisionInputHash = "a".repeat(HASH_LENGTH),
    )

    private fun stubApp(app: LoanApplication) {
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
    }

    private fun request(app: LoanApplication, owner: UUID = party): DeclineReviewResult =
        service.requestReview(app.id.value, owner).await().indefinitely()

    private fun refusalCode(block: () -> Any): String {
        val e = catchThrowable(block)
        assertThat(e).isInstanceOf(DeclineReviewRefusedException::class.java)
        return (e as DeclineReviewRefusedException).code
    }

    private fun catchThrowable(block: () -> Any): Throwable? = try {
        block()
        null
    } catch (e: DeclineReviewRefusedException) {
        e
    }

    // --- request guards -------------------------------------------------------------------------------

    @Test
    fun `an engine decline inside the window opens a REQUESTED review with the engine snapshot and one event`() {
        val app = declined().also(::stubApp)

        val result = request(app)

        assertThat(result.created).isTrue()
        assertThat(result.review.status).isEqualTo(com.openbank.lending.domain.model.DeclineReviewStatus.REQUESTED)
        assertThat(result.review.engineReasons).isEqualTo("AFFORDABILITY_FAILED:starter-aff-dsti")
        assertThat(result.review.policyVersions).isEqualTo("ELIGIBILITY=1,AFFORDABILITY=1")
        assertThat(result.review.engineOutcome).isEqualTo("DECLINE")
        assertThat(emitted.map { it.eventType }).containsExactly("credit.decline_review.requested")
        val payload = ObjectMapper().readTree(emitted.single().payload)
        assertThat(payload["schemaVersion"].asInt()).isEqualTo(1)
        assertThat(
            payload["engineReasonCodes"].map {
                it.asText()
            },
        ).containsExactly("AFFORDABILITY_FAILED:starter-aff-dsti")
    }

    @Test
    fun `a decline already made or confirmed by a human is refused - the loop guard`() {
        val app = declined(decidedBy = "officer-1").also(::stubApp)

        assertThat(refusalCode { request(app) }).isEqualTo(DeclineReviewRefusedException.NOT_ENGINE_DECLINE)
        verify(exactly = 0) { reviews.insert(any()) }
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `an application that is not DECLINED, or was not declined by the engine, is refused`() {
        val referred = declined(status = OriginationState.FOUR_EYES, outcome = "REFER").also(::stubApp)
        assertThat(refusalCode { request(referred) }).isEqualTo(DeclineReviewRefusedException.NOT_ENGINE_DECLINE)

        val noEngineEvidence = declined(outcome = null, engineAt = null).also(::stubApp)
        assertThat(
            refusalCode {
                request(noEngineEvidence)
            },
        ).isEqualTo(DeclineReviewRefusedException.NOT_ENGINE_DECLINE)
    }

    @Test
    fun `the window is bounded - one day inside is accepted, one second past is refused`() {
        val inside = declined(
            engineAt = OffsetDateTime.ofInstant(now.minusSeconds(WINDOW_DAYS * DAY - 1), ZoneOffset.UTC),
        ).also(::stubApp)
        assertThat(request(inside).created).isTrue()

        val past = declined(
            engineAt = OffsetDateTime.ofInstant(now.minusSeconds(WINDOW_DAYS * DAY + 1), ZoneOffset.UTC),
        ).also(::stubApp)
        assertThat(refusalCode { request(past) }).isEqualTo(DeclineReviewRefusedException.WINDOW_ELAPSED)
    }

    @Test
    fun `only the applicant may request - a foreign party reads as not found`() {
        val app = declined().also(::stubApp)

        assertThat(
            refusalCode {
                request(app, owner = UUID.randomUUID())
            },
        ).isEqualTo(DeclineReviewRefusedException.NOT_FOUND)
        verify(exactly = 0) { reviews.insert(any()) }
    }

    @Test
    fun `a repeat request returns the existing review and writes nothing`() {
        val app = declined().also(::stubApp)
        val existing = DeclineReview(
            UUID.randomUUID(),
            app.id.value,
            party,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            "DECLINE",
            null,
            null,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        )
        every { reviews.loadByApplication(app.id.value) } returns Uni.createFrom().item(existing)

        val result = request(app)

        assertThat(result.created).isFalse()
        assertThat(result.review).isEqualTo(existing)
        verify(exactly = 0) { reviews.insert(any()) }
        assertThat(emitted).isEmpty()
    }

    private companion object {
        const val WINDOW_DAYS = 30L
        const val DAY = 86_400L
        const val HASH_LENGTH = 64
    }
}
