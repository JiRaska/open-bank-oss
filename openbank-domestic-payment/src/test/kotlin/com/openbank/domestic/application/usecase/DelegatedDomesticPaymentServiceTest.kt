// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentResult
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DelegatedPaymentSaveOutcome
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticTransferScope
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DelegatedDomesticPaymentServiceTest {
    private val paymentRepository = mockk<DomesticPaymentRepository>()
    private val bindingRepository = mockk<DelegatedSpendBindingRepository>()
    private val eventPublisher = mockk<DomesticPaymentEventPublisher>()
    private val accountLookup = mockk<AccountLookupPort>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val workflowClient = mockk<WorkflowClient>(relaxed = true)
    private val service = DomesticPaymentService(
        paymentRepository = paymentRepository,
        delegatedSpendBindingRepository = bindingRepository,
        eventPublisher = eventPublisher,
        accountLookupPort = accountLookup,
        metrics = metrics,
        temporalTaskQueue = "openbank-domestic-payments",
        workflowClient = workflowClient,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    @BeforeEach
    fun setUp() {
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
        coEvery { paymentRepository.findByIdempotencyKey(any()) } returns null
        coEvery { accountLookup.findAccountIdByIban(DEBTOR_IBAN) } returns ACCOUNT_ID
        coEvery { accountLookup.findPartyByAccountId(ACCOUNT_ID) } returns GRANTOR_ID
        coEvery { accountLookup.findPartyByIban(any()) } returns GRANTEE_ID
    }

    @Test
    fun `missing local projection returns the future 425 semantic without touching payment storage`() = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns null

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.ReservationProjectionPending)
        coVerify(exactly = 0) { paymentRepository.saveDelegated(any(), any(), any(), any()) }
    }

    @Test
    fun `permanent absence tombstone returns the future 410 semantic`() = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.FINALIZED_ABSENT,
        )

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.ReservationFinalizedAbsent)
        coVerify(exactly = 0) { paymentRepository.saveDelegated(any(), any(), any(), any()) }
    }

    @Test
    fun `debtor coordinates resolving to another account fail closed without creation`() = runBlocking {
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
    fun `reserved account owned by another party fails closed without creation`() = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.PENDING,
        )
        coEvery { accountLookup.findPartyByAccountId(ACCOUNT_ID) } returns UUID.randomUUID()

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isInstanceOfSatisfying(DelegatedDomesticPaymentResult.ReservationMismatch::class.java) {
            assertThat(it.reason).contains("delegation grantor")
        }
        assertNoCreationSideEffects()
    }

    @Test
    fun `unavailable account authority is retryable and has no creation side effects`() = runBlocking {
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
    fun `unavailable account owner authority is retryable and has no creation side effects`() = runBlocking {
        coEvery { bindingRepository.findByReservationId(RESERVATION_ID) } returns binding(
            DelegatedSpendBindingState.PENDING,
        )
        coEvery { accountLookup.findPartyByAccountId(ACCOUNT_ID) } returns null

        val result = service.createDelegatedPayment(RESERVATION_ID, command())

        assertThat(result).isEqualTo(DelegatedDomesticPaymentResult.AccountAuthorityUnavailable)
        assertNoCreationSideEffects()
    }

    @Test
    fun `projection supplies trusted tuple and debit owner even when command attempts to override it`() = runBlocking {
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
        coVerify(exactly = 1) { accountLookup.findPartyByAccountId(ACCOUNT_ID) }
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
        amount = BigDecimal("1500.00"),
        currency = "CZK",
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        statementLabel = null,
        endToEndId = "DOM-DELEGATED-TEST",
    )

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
}
