// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.DomesticPaymentReceiptQuery
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.mockk
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DomesticPaymentReceiptTest {
    private val repository = mockk<DomesticPaymentRepository>()
    private val accounts = mockk<AccountLookupPort>()
    private val service = DomesticPaymentService(
        repository,
        mockk<DelegatedSpendBindingRepository>(),
        mockk<DomesticPaymentEventPublisher>(),
        accounts,
        mockk<DomainMetrics>(),
        "test",
        mockk<WorkflowClient>(),
        java.time.Clock.systemUTC(),
    )

    @Test
    fun `receipt discloses only an issuer bound actor's currently owned account`(): Unit = runBlocking {
        val actor = UUID.randomUUID()
        val account = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        val scope = "https://issuer.example\u001f$actor"
        val payment = mockk<DomesticPayment>()
        io.mockk.every { payment.id } returns paymentId
        io.mockk.every { payment.status } returns DomesticPaymentStatus.RECEIVED
        io.mockk.every { payment.debtorAccountId } returns account
        io.mockk.every { payment.initiatedByPartyId } returns actor
        io.mockk.every { payment.delegationId } returns null
        io.mockk.every { payment.reservationId } returns null
        io.mockk.every { payment.requestFingerprint } returns "a".repeat(64)
        io.mockk.every { payment.receiptActorScopeHash } returns ReceiptActorScope.hash(scope)
        coEvery { repository.findByIdempotencyKey("secret") } returns payment
        coEvery { accounts.findPartyByAccountId(account) } returns actor

        val query = DomesticPaymentReceiptQuery("secret", account, actor, scope)
        assertThat(service.findReceipt(query).paymentId).isEqualTo(paymentId)
        assertThat(service.findReceipt(query.copy(actorScope = "https://other.example\u001f$actor")).outcome)
            .isEqualTo("UNKNOWN")
        assertThat(service.findReceipt(query.copy(debtorAccountId = UUID.randomUUID())).outcome)
            .isEqualTo("UNKNOWN")
        assertThat(service.findReceipt(query.copy(actorId = UUID.randomUUID())).outcome)
            .isEqualTo("UNKNOWN")
        io.mockk.every { payment.receiptActorScopeHash } returns null
        assertThat(service.findReceipt(query).outcome).isEqualTo("UNKNOWN")
        io.mockk.every { payment.receiptActorScopeHash } returns ReceiptActorScope.hash(scope)
        coEvery { accounts.findPartyByAccountId(account) } returns null
        assertThat(service.findReceipt(query).outcome).isEqualTo("UNKNOWN")
    }
}
