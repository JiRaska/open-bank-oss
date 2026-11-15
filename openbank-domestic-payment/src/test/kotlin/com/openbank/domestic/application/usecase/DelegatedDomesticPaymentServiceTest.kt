// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentResult
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DelegatedPaymentSaveOutcome
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.port.out.DelegationAuthorityPort
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.error.DomesticSchemeErrorCode
import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.money.Money
import com.openbank.libs.fincrime.admission.DelegationFact
import com.openbank.libs.fincrime.admission.DelegationFactsLookup
import com.openbank.libs.fincrime.admission.UnavailableCause
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.temporal.client.WorkflowClient
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

class DelegatedDomesticPaymentServiceTest {
    private val paymentRepository = mockk<DomesticPaymentRepository>()
    private val bindingRepository = mockk<DelegatedSpendBindingRepository>()
    private val eventPublisher = mockk<DomesticPaymentEventPublisher>()
    private val accountLookup = mockk<AccountLookupPort>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val workflowClient = mockk<WorkflowClient>(relaxed = true)
    private val authorityPort = mockk<DelegationAuthorityPort>()
    private val service = DomesticPaymentService(
        paymentRepository = paymentRepository,
        delegatedSpendBindingRepository = bindingRepository,
        eventPublisher = eventPublisher,
        accountLookupPort = accountLookup,
        metrics = metrics,
        temporalTaskQueue = "openbank-domestic-payments",
        workflowClient = workflowClient,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    ).also { it.delegationAuthorityPort = authorityPort }

    @BeforeEach
    fun setUp() {
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
        coEvery { paymentRepository.findByIdempotencyKey(any()) } returns null
        coEvery { accountLookup.findAccountIdByIban(DEBTOR_IBAN) } returns ACCOUNT_ID
        coEvery { accountLookup.findPartyByAccountId(ACCOUNT_ID) } returns GRANTOR_ID
        coEvery { accountLookup.findPartyByIban(any()) } returns GRANTEE_ID
        // The live grant is the DEFAULT so that every pre-existing test keeps asserting what it
        // was written to assert. A test that wants the authority step to bite overrides it.
        coEvery { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) } returns
            DelegationFactsLookup.Available(listOf(liveGrant()), GRANTOR_ID)
    }

    private fun liveGrant(capabilities: Set<String> = setOf("ACCOUNT_INITIATE_PAYMENT")) = DelegationFact(
        grantId = DELEGATION_ID,
        grantorPartyId = GRANTOR_ID,
        resourceId = ACCOUNT_ID,
        granteePartyId = GRANTEE_ID,
        capabilities = capabilities,
        active = true,
        validFrom = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(1),
        validTo = null,
    )

    @Test
    fun `non-CZK delegated command is rejected before reading reservation projection`() {
        assertThatThrownBy {
            runBlocking {
                service.createDelegatedPayment(
                    RESERVATION_ID,
                    command().copy(amount = Money.of(BigDecimal("1500.00"), "EUR")),
                )
            }
        }.isInstanceOfSatisfying(ValidationFailure::class.java) {
            assertThat(it.errorCode).isEqualTo(DomesticSchemeErrorCode.CURRENCY_NOT_ALLOWED)
        }
        coVerify(exactly = 0) { bindingRepository.findByReservationId(any()) }
        assertNoCreationSideEffects()
    }

    @Test
    fun `missing local projection returns the future 425 semantic without touching payment storage`(): Unit =
        runBlocking {
            coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns null

            val result = service.createDelegatedPayment(RESERVATION_ID, command())

            assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.ReservationProjectionPending)
            coVerify(exactly = 0) { paymentRepository.saveDelegated(any(), any(), any(), any()) }
        }

    @Test
    fun `permanent absence tombstone returns the future 410 semantic`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.FINALIZED_ABSENT,
        )

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.ReservationFinalizedAbsent)
        coVerify(exactly = 0) { paymentRepository.saveDelegated(any(), any(), any(), any()) }
    }

    @Test
    fun `debtor coordinates resolving to another account fail closed without creation`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.PENDING,
        )
        coEvery { accountLookup.findAccountIdByIban(DEBTOR_IBAN) } returns UUID.randomUUID()

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isInstanceOfSatisfying(DelegatedDomesticPaymentResult.ReservationMismatch::class.java) {
            assertThat(it.reason).contains("reserved account")
        }
        coVerify(exactly = 0) { accountLookup.findPartyByAccountId(any()) }
        assertNoCreationSideEffects()
    }

    @Test
    fun `reserved account owned by another party fails closed without creation`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.PENDING,
        )
        // Retargeted to the single read: the owner the mismatch check compares is the one that
        // came with the grants, which is the whole point — it can no longer differ from the owner
        // the write-time proof compares.
        coEvery { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) } returns
            DelegationFactsLookup.Available(listOf(liveGrant()), UUID.randomUUID())

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isInstanceOfSatisfying(DelegatedDomesticPaymentResult.ReservationMismatch::class.java) {
            assertThat(it.reason).contains("delegation grantor")
        }
        assertNoCreationSideEffects()
    }

    @Test
    fun `unavailable account authority is retryable and has no creation side effects`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.PENDING,
        )
        coEvery { accountLookup.findAccountIdByIban(DEBTOR_IBAN) } returns null

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.AccountAuthorityUnavailable)
        coVerify(exactly = 0) { accountLookup.findPartyByAccountId(any()) }
        assertNoCreationSideEffects()
    }

    @Test
    fun `unavailable account owner authority is retryable and has no creation side effects`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.PENDING,
        )
        // Retargeted: the owner now arrives WITH the grants (ADR-0375 D1), so what makes the owner
        // unresolvable is the authority read failing, not a separate owner lookup. The property is
        // unchanged — an unresolvable owner is retryable and writes nothing.
        coEvery { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) } returns
            DelegationFactsLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN, "timeout")

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.AccountAuthorityUnavailable)
        assertNoCreationSideEffects()
    }

    @Test
    fun `projection supplies trusted tuple and debit owner even when command attempts to override it`(): Unit =
        runBlocking {
            val projected = binding(DelegatedSpendBindingState.PENDING)
            val capturedPayment = slot<DomesticPayment>()
            val capturedDebitOwner = slot<UUID>()
            coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns projected
            coEvery {
                paymentRepository.saveDelegated(
                    capture(capturedPayment),
                    any(),
                    any(),
                    capture(capturedDebitOwner),
                )
            } answers {
                val candidate = capturedPayment.captured
                DelegatedPaymentSaveOutcome.Replayed(candidate.copy(id = UUID.randomUUID()))
            }
            val maliciousDelegation = UUID.randomUUID()
            val maliciousReservation = UUID.randomUUID()
            val maliciousActor = UUID.randomUUID()
            val maliciousAccount = UUID.randomUUID()

            val result = service.createDelegatedPayment(
                RESERVATION_ID,
                command().copy(
                    actorId = maliciousActor,
                    actorScope = "attacker-controlled",
                    delegationId = maliciousDelegation,
                    reservationId = maliciousReservation,
                    debtorAccountId = maliciousAccount,
                ),
            )

            assertThat(result).isInstanceOf(DelegatedDomesticPaymentResult.Accepted::class.java)
            assertThat(capturedPayment.captured.initiatedByPartyId).isEqualTo(GRANTEE_ID)
            assertThat(capturedPayment.captured.delegationId).isEqualTo(DELEGATION_ID)
            assertThat(capturedPayment.captured.reservationId).isEqualTo(RESERVATION_ID)
            assertThat(capturedPayment.captured.debtorAccountId).isEqualTo(ACCOUNT_ID)
            assertThat(capturedDebitOwner.captured).isEqualTo(GRANTOR_ID)
            assertThat(capturedPayment.captured.transferScope).isEqualTo(DomesticTransferScope.INTERNAL_CLIENT)
            coVerify(exactly = 1) { accountLookup.findAccountIdByIban(DEBTOR_IBAN) }
            // The debit owner is still resolved independently of the command — now from the
            // authority read, which is the same answer the authority decision used, so the proof
            // and the decision cannot disagree.
            coVerify(exactly = 1) { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) }
        }

    private fun assertNoCreationSideEffects() {
        coVerify(exactly = 0) { paymentRepository.findByIdempotencyKey(any()) }
        coVerify(exactly = 0) { paymentRepository.saveDelegated(any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.paymentCreatedPayload(any()) }
    }

    private fun command() = CreateDomesticPaymentCommand(
        idempotencyKey = "payment-42",
        debtorAccountId = ACCOUNT_ID,
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Grantor",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "0000",
        creditorName = "Grantee",
        amount = Money.of(BigDecimal("1500.00"), "CZK"),
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        statementLabel = null,
        endToEndId = "DOM-DELEGATED-TEST",
    )

    /**
     * The binding as the CREATE path finds it: PENDING, i.e. the reservation snapshot has been
     * projected and no payment is bound to it yet. BOUND would be wrong here and the invariant
     * says so — it requires a paymentId and a boundAt, which is the state AFTER a create.
     */
    private fun pendingBinding() = binding(DelegatedSpendBindingState.PENDING)

    private fun binding(state: DelegatedSpendBindingState) = DelegatedSpendBinding(
        snapshot = DelegatedSpendReservationSnapshot(
            eventId = UUID.randomUUID(),
            reservationId = RESERVATION_ID,
            delegationId = DELEGATION_ID,
            grantorPartyId = GRANTOR_ID,
            granteePartyId = GRANTEE_ID,
            resourceType = "ACCOUNT",
            resourceId = ACCOUNT_ID,
            amount = BigDecimal("1500.00"),
            currency = "CZK",
            idempotencyKeyHash = DelegatedSpendReservationSnapshot.hashIdempotencyKey("payment-42"),
            operationType = "DOMESTIC_PAYMENT",
            reservationState = DelegatedSpendReservationState.RESERVED,
            reservationVersion = 1,
            schemaVersion = 1,
            aggregateType = "DelegationSpendReservation",
            sourceService = "delegation-service",
            createdAt = NOW.minusSeconds(60),
            settledAt = null,
            occurredAt = NOW.minusSeconds(60),
        ),
        bindingState = state,
        paymentId = null,
        observedAt = NOW.minusSeconds(30),
        boundAt = null,
        finalizedAt = NOW.takeIf { state == DelegatedSpendBindingState.FINALIZED_ABSENT },
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-01T12:10:00Z")
        val RESERVATION_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000001")
        val DELEGATION_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000002")
        val GRANTOR_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000003")
        val GRANTEE_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000004")
        val ACCOUNT_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000005")
        const val DEBTOR_IBAN = "CZ0708000000001234567890"
    }

    // ── the authority step (ADR-0375 D1) ──────────────────────────────────────────────────

    /**
     * The hole this closes. The reservation proves authority AS AT RESERVE TIME —
     * `SpendReservationService.evaluateLockedGrant` checks `ACCOUNT_INITIATE_PAYMENT` under a row
     * lock — and nothing re-checks it afterwards: `DelegationService.revoke` emits
     * `DelegationRevoked` and releases no reservation, and `SpendReservation` has no TTL. So a
     * RESERVED row outlives the grant that bought it, by an unbounded interval.
     */
    @Test
    fun `a grant revoked after the reservation no longer buys the payment`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns pendingBinding()
        coEvery { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) } returns
            DelegationFactsLookup.Available(emptyList(), GRANTOR_ID)

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isInstanceOf(DelegatedDomesticPaymentResult.DelegationAuthorityWithdrawn::class.java)
        assertNoCreationSideEffects()
    }

    @Test
    fun `a grant that lost the payment capability no longer buys the payment`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns pendingBinding()
        coEvery { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) } returns
            DelegationFactsLookup.Available(
                listOf(liveGrant(capabilities = setOf("ACCOUNT_READ_BALANCES"))),
                GRANTOR_ID,
            )

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isInstanceOf(DelegatedDomesticPaymentResult.DelegationAuthorityWithdrawn::class.java)
        assertNoCreationSideEffects()
    }

    /**
     * The half that is just as load-bearing: an outage must NOT read as a withdrawn mandate. It
     * maps to the existing retryable result, so the caller retries instead of telling the customer
     * their authority was taken away.
     */
    @Test
    fun `an unreadable authority holds rather than refusing`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns pendingBinding()
        coEvery { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) } returns
            DelegationFactsLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN, "timeout")

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.AccountAuthorityUnavailable)
        assertNoCreationSideEffects()
    }

    @Test
    fun `the authority is asked about the payment capability, not whatever the grant happens to hold`(): Unit =
        runBlocking {
            coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns pendingBinding()
            coEvery { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) } returns
                DelegationFactsLookup.Available(
                    listOf(liveGrant(capabilities = setOf("ACCOUNT_READ_TRANSACTIONS", "ACCOUNT_VIEW_DETAILS"))),
                    GRANTOR_ID,
                )

            val result = service.createDelegatedPayment(RESERVATION_ID, command())

            // Two read capabilities are not "close enough" to a debit. Accepting any capability
            // the grant happens to carry is the ADR-0375 D7 defect, one layer up.
            assertThat(result).isInstanceOf(DelegatedDomesticPaymentResult.DelegationAuthorityWithdrawn::class.java)
        }

    @Test
    fun `the authority is asked about the reserved account and the grantee, not the request`(): Unit = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns pendingBinding()
        val candidate = slot<DomesticPayment>()
        coEvery { paymentRepository.saveDelegated(capture(candidate), any(), any(), any()) } answers {
            DelegatedPaymentSaveOutcome.Replayed(candidate.captured.copy(id = UUID.randomUUID()))
        }

        // The command deliberately names a DIFFERENT account and actor; neither may reach the port.
        service.createDelegatedPayment(
            RESERVATION_ID,
            command().copy(debtorAccountId = UUID.randomUUID(), actorId = UUID.randomUUID()),
        )

        // Both arguments come from the durable reservation snapshot. Taking either from the
        // command would let a caller pick the account whose grants are consulted.
        coVerify(exactly = 1) { authorityPort.factsFor(ACCOUNT_ID, GRANTEE_ID) }
    }
}
