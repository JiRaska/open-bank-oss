// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.ReserveSpendCommand
import com.openbank.delegation.application.port.`in`.ReserveSpendResult
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.ReserveOutcome
import com.openbank.delegation.application.port.out.SpendReservationRepository
import com.openbank.delegation.domain.model.CountedSpend
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.SpendDecision
import com.openbank.delegation.domain.model.SpendRefusalReason
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.delegation.domain.model.SpendWindow
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0249 D3 at the use-case seam.
 *
 * The repository fake is not a mock returning canned outcomes: it keeps reservations in a map and
 * runs the REAL decide callback against the REAL counted totals, so the tests exercise the actual
 * counting rules — reserved counts, confirmed counts, released does not — rather than an
 * arrangement that asserts the rules back to itself. Atomicity is the one thing it cannot model;
 * that lives in `SpendReservationConcurrencyIT` against a real Postgres.
 */
class SpendReservationServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC)
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()

    private val delegationRepository: DelegationRepository = mockk()
    private val reservations = InMemorySpendReservationRepository { currentGrant }

    private lateinit var service: SpendReservationService

    private fun czk(amount: String) = Money.of(amount, "CZK")

    private fun grant(
        daily: Money? = czk("5000.00"),
        monthly: Money? = null,
        perTx: Money? = null,
        status: DelegationStatus = DelegationStatus.ACTIVE,
    ) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
        perTransactionLimit = perTx,
        dailyLimit = daily,
        monthlyLimit = monthly,
        validFrom = now.minusDays(1),
        validTo = now.plusDays(30),
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    private lateinit var currentGrant: DelegationGrant

    @BeforeEach
    fun setUp() {
        service = SpendReservationService(delegationRepository, reservations, clock)
        currentGrant = grant()
        coEvery { delegationRepository.findById(any()) } answers { currentGrant }
    }

    // Block body, not `= runBlocking {`: the CI guard flags that form because a @Test written
    // that way returns non-Unit and JUnit5 silently drops it. This helper is not a @Test and does
    // return a value, but the guard reads shape rather than intent — and a shape that is unsafe on
    // the tests next to it is not worth defending here.
    private fun reserve(amount: String, key: String = UUID.randomUUID().toString()): ReserveSpendResult {
        // Two statements, not one: ktlint's function-expression-body rule would otherwise demand
        // the `= runBlocking {` form back, which is exactly what the CI guard forbids.
        val command = ReserveSpendCommand(
            callerPartyId = grantee,
            delegationId = currentGrant.id,
            amount = czk(amount),
            idempotencyKey = key,
        )
        return runBlocking { service.reserve(command) }
    }

    @Test
    fun `a reserved amount counts against the ceiling of the next reserve`() {
        reserve("3000.00")

        assertThatThrownBy { reserve("2500.00") }
            .isInstanceOf(SpendReservationRefusedException::class.java)
            .extracting { (it as SpendReservationRefusedException).decision.reason }
            .isEqualTo(SpendRefusalReason.DAILY)
    }

    /**
     * The idempotency rule: the same key returns the SAME reservation and takes the headroom once.
     * Without it a rail replaying a payment would eat the customer's ceiling twice for one spend.
     */
    @Test
    fun `reserving twice under one idempotency key returns the same reservation and counts once`() {
        val first = reserve("3000.00", key = "payment-42")
        val second = reserve("3000.00", key = "payment-42")

        assertThat(second.reservation.id).isEqualTo(first.reservation.id)
        assertThat(first.replayed).isFalse()
        assertThat(second.replayed).isTrue()
        // 3 000 counted once, so 2 000 of the 5 000 ceiling is still there.
        assertThat(reserve("2000.00").reservation.state).isEqualTo(SpendReservationState.RESERVED)
    }

    @Test
    fun `releasing a reservation restores the headroom`() {
        val first = reserve("4000.00")
        assertThatThrownBy { reserve("2000.00") }.isInstanceOf(SpendReservationRefusedException::class.java)

        runBlocking { service.release(currentGrant.id, first.reservation.id, grantee) }

        assertThat(reserve("2000.00").reservation.state).isEqualTo(SpendReservationState.RESERVED)
    }

    @Test
    fun `confirming keeps the headroom consumed`() {
        val first = reserve("4000.00")

        val confirmed = runBlocking { service.confirm(currentGrant.id, first.reservation.id, grantee) }

        assertThat(confirmed.state).isEqualTo(SpendReservationState.CONFIRMED)
        assertThat(confirmed.settledAt).isEqualTo(now)
        assertThatThrownBy { reserve("2000.00") }.isInstanceOf(SpendReservationRefusedException::class.java)
    }

    @Test
    fun `confirming twice is a no-op rather than a conflict`() {
        val first = reserve("100.00")
        runBlocking { service.confirm(currentGrant.id, first.reservation.id, grantee) }

        val again = runBlocking { service.confirm(currentGrant.id, first.reservation.id, grantee) }

        assertThat(again.state).isEqualTo(SpendReservationState.CONFIRMED)
    }

    /** Releasing settled money would silently re-open a ceiling the delegate has already spent. */
    @Test
    fun `releasing a confirmed reservation is refused`() {
        val first = reserve("100.00")
        runBlocking { service.confirm(currentGrant.id, first.reservation.id, grantee) }

        assertThatThrownBy { runBlocking { service.release(currentGrant.id, first.reservation.id, grantee) } }
            .isInstanceOf(SpendReservationStateException::class.java)
    }

    @Test
    fun `a monthly ceiling refuses even when today is untouched`() {
        // The daily ceiling is deliberately generous, so the refusal can only come from the month.
        currentGrant = grant(daily = czk("10000.00"), monthly = czk("6000.00"))
        reserve("4000.00")
        runBlocking { service.confirm(currentGrant.id, reservations.all().first().id, grantee) }

        assertThatThrownBy { reserve("2500.00") }
            .isInstanceOf(SpendReservationRefusedException::class.java)
            .extracting { (it as SpendReservationRefusedException).decision.reason }
            .isEqualTo(SpendRefusalReason.MONTHLY)
    }

    /**
     * The reservation endpoint would otherwise be an oracle for how much of a stranger's ceiling is
     * left — 404 rather than 403, for the same reason `getDelegation` does not confirm existence.
     */
    @Test
    fun `a caller who is not the grantee cannot reserve`() {
        assertThatThrownBy {
            runBlocking {
                service.reserve(
                    ReserveSpendCommand(
                        callerPartyId = UUID.randomUUID(),
                        delegationId = currentGrant.id,
                        amount = czk("1.00"),
                        idempotencyKey = "k",
                    ),
                )
            }
        }.isInstanceOf(DelegationNotFoundException::class.java)
    }

    @Test
    fun `settling a reservation that does not exist is a 404`() {
        assertThatThrownBy { runBlocking { service.confirm(currentGrant.id, UUID.randomUUID(), grantee) } }
            .isInstanceOf(SpendReservationNotFoundException::class.java)
    }

    /**
     * Counts for real instead of returning canned answers, so the "reserved counts / confirmed
     * counts / released does not" rule is exercised by every test above rather than restated.
     */
    private class InMemorySpendReservationRepository(private val grant: () -> DelegationGrant) :
        SpendReservationRepository {
        private val rows = ConcurrentHashMap<UUID, SpendReservation>()

        fun all(): List<SpendReservation> = rows.values.toList()

        override suspend fun reserve(
            candidate: SpendReservation,
            window: SpendWindow,
            decide: (DelegationGrant, CountedSpend) -> SpendDecision,
        ): ReserveOutcome {
            rows.values.firstOrNull {
                it.grantId == candidate.grantId && it.idempotencyKey == candidate.idempotencyKey
            }?.let { return ReserveOutcome.Replayed(it) }

            val currency = candidate.amount.currency
            fun sumSince(from: OffsetDateTime): Money = rows.values
                .filter {
                    it.grantId == candidate.grantId &&
                        it.amount.currency == currency &&
                        it.countsTowardCeilings &&
                        !it.createdAt.isBefore(from)
                }
                .fold(BigDecimal.ZERO) { acc, r -> acc.add(r.amount.amount) }
                .let { Money(it.setScale(currency.defaultFractionDigits), currency) }

            val counted = CountedSpend(sumSince(window.dayStart), sumSince(window.monthStart))
            return when (val decision = decide(grant(), counted)) {
                is SpendDecision.Refused -> ReserveOutcome.Refused(decision)
                SpendDecision.Allowed -> {
                    rows[candidate.id] = candidate
                    ReserveOutcome.Created(candidate)
                }
            }
        }

        override suspend fun findById(grantId: UUID, reservationId: UUID): SpendReservation? =
            rows[reservationId]?.takeIf { it.grantId == grantId }

        override suspend fun settle(
            grantId: UUID,
            reservationId: UUID,
            target: SpendReservationState,
            settledAt: OffsetDateTime,
        ): SpendReservation? {
            val existing = findById(grantId, reservationId) ?: return null
            if (existing.state != SpendReservationState.RESERVED) return null
            val settled = when (target) {
                SpendReservationState.CONFIRMED -> existing.confirm(settledAt)
                SpendReservationState.RELEASED -> existing.release(settledAt)
                SpendReservationState.RESERVED -> return null
            }
            rows[reservationId] = settled
            return settled
        }
    }
}
