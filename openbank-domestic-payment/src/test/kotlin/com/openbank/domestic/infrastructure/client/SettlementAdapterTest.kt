// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SettlementAdapterTest {

    private val client: TransactionServiceClient = mockk()
    private val accountLookup: AccountLookupPort = mockk()
    private val txId = UUID.randomUUID()
    private val debtor = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun adapter(): SettlementAdapter =
        SettlementAdapter(client, accountLookup, Clock.systemUTC()).also { it.self = it }

    @BeforeEach
    fun setUp() {
        // Default: no internal creditor resolved → debit-only booking (existing behaviour).
        coEvery { accountLookup.findAccountIdByIban(any()) } returns null
    }

    /** A COMPLETED transaction that echoes whatever request was sent: the matching booking. */
    private fun echo(request: InitiateTransactionRequest, status: String = "COMPLETED") = TransactionResponse(
        id = txId,
        status = status,
        type = request.type,
        sourceAccountId = request.sourceAccountId,
        targetAccountId = request.targetAccountId,
        amount = request.amount,
        currencyCode = request.currencyCode,
        rail = request.rail,
        instructionType = request.instructionType,
        valueDate = LocalDate.parse("2030-01-02"),
    )

    /** Answers every call with [status] and the body [body] builds from the request actually sent. */
    private fun answer(status: Int = 201, body: (InitiateTransactionRequest) -> TransactionResponse? = { echo(it) }) {
        val sent = slot<InitiateTransactionRequest>()
        every { client.initiateTransaction(capture(sent)) } answers {
            val builder = Response.status(status)
            body(sent.captured)?.let { builder.entity(it) }
            Uni.createFrom().item(builder.build())
        }
    }

    private fun settleFails(p: DomesticPayment = payment()): Throwable =
        runCatching { runBlocking { adapter().settle(p) } }.exceptionOrNull()
            ?: error("settle() unexpectedly succeeded")

    @Test
    fun `settle returns settled=true and transactionId on a matching COMPLETED HTTP 201`(): Unit = runBlocking {
        answer()

        val outcome = adapter().settle(payment())

        assertThat(outcome.settled).isTrue()
        assertThat(outcome.transactionId).isEqualTo(txId)
    }

    @Test
    fun `HTTP 409 is a concurrent-state conflict - never settled`() {
        answer(409) { null }

        assertThat(settleFails())
            .isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("HTTP 409")
    }

    @Test
    fun `each mismatched field of a COMPLETED 201 is not a settlement`() {
        val mismatches: Map<String, (InitiateTransactionRequest) -> TransactionResponse> = mapOf(
            "type" to { r -> echo(r).copy(type = "CREDIT") },
            "source account" to { r -> echo(r).copy(sourceAccountId = UUID.randomUUID()) },
            "target account" to { r -> echo(r).copy(targetAccountId = UUID.randomUUID()) },
            "amount" to { r -> echo(r).copy(amount = r.amount.add(BigDecimal("0.01"))) },
            "currency" to { r -> echo(r).copy(currencyCode = "EUR") },
            "rail" to { r -> echo(r).copy(rail = "SEPA_CT") },
            "instructionType" to { r -> echo(r).copy(instructionType = "STANDING_ORDER") },
        )
        val creditor = UUID.randomUUID()
        coEvery { accountLookup.findAccountIdByIban(any()) } returns creditor
        mismatches.forEach { (field, body) ->
            answer(body = body)
            assertThat(settleFails())
                .describedAs("mismatched %s", field)
                .isInstanceOf(SettlementUnavailableException::class.java)
                .hasMessageContaining("does not match")
        }
    }

    @Test
    fun `HTTP 200 or 202 with a COMPLETED body is not the provider's answer to a create`() {
        listOf(200, 202).forEach { code ->
            answer(code)
            assertThat(settleFails()).describedAs("HTTP %d", code)
                .isInstanceOf(SettlementUnavailableException::class.java)
                .hasMessageContaining("HTTP $code")
        }
    }

    @Test
    fun `a PENDING 201 or 202 is completion-uncertain - never settled`() {
        listOf(201, 202).forEach { code ->
            answer(code) { echo(it, status = "PENDING") }
            assertThat(settleFails()).hasCauseInstanceOf(TransactionCompletionUncertainException::class.java)
        }
    }

    @Test
    fun `settle throws SettlementUnavailableException on HTTP 500`() {
        answer(500) { null }
        assertThat(settleFails()).isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `settle throws SettlementUnavailableException on connection failure`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThat(settleFails()).isInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `cancellation is rethrown, not converted into an outage`() {
        every { client.initiateTransaction(any()) } returns
            Uni.createFrom().failure(CancellationException("worker shutting down"))

        assertThatThrownBy { runBlocking { adapter().settle(payment()) } }
            .isInstanceOf(CancellationException::class.java)
            .isNotInstanceOf(SettlementUnavailableException::class.java)
    }

    @Test
    fun `settle sends correct idempotencyKey and rail`(): Unit = runBlocking {
        val p = payment()
        val requestSlot = slot<InitiateTransactionRequest>()
        every { client.initiateTransaction(capture(requestSlot)) } answers {
            Uni.createFrom().item(Response.status(201).entity(echo(requestSlot.captured)).build())
        }

        adapter().settle(p)

        val req = requestSlot.captured
        assertThat(req.idempotencyKey).isEqualTo("domestic-settlement-${p.id}")
        assertThat(req.rail).isEqualTo("DOMESTIC")
        assertThat(req.type).isEqualTo("DEBIT")
        assertThat(req.instructionType).isEqualTo("ONE_OFF")
        assertThat(req.sourceAccountId).isEqualTo(p.debtorAccountId)
        assertThat(req.amount).isEqualByComparingTo(p.amount.amount)
        assertThat(req.currencyCode).isEqualTo(p.currency)
    }

    @Test
    fun `settle books the Money amount at the currency's minor-unit scale without rounding`(): Unit = runBlocking {
        // #11604: the NUMERIC(20,6) column value 10.130000 reads back as Money 10.13 CZK. The booking
        // carries exactly that — scale 2, which transaction-service requires — and no rounding step
        // exists any more: an amount with a third decimal can no longer reach this adapter.
        val p = payment().copy(amount = Money.of(BigDecimal("10.130000"), "CZK"))
        val requestSlot = slot<InitiateTransactionRequest>()
        every { client.initiateTransaction(capture(requestSlot)) } answers {
            Uni.createFrom().item(Response.status(201).entity(echo(requestSlot.captured)).build())
        }

        adapter().settle(p)

        assertThat(requestSlot.captured.amount).isEqualTo(BigDecimal("10.13"))
        assertThat(requestSlot.captured.amount.scale()).isEqualTo(2)
    }

    @Test
    fun `settle resolves the payee and sends targetAccountId for an internal transfer`(): Unit = runBlocking {
        val p = payment() // INTERNAL_CLIENT, creditor 9876543210 / bank 0100
        val creditorId = UUID.randomUUID()
        val ibanSlot = slot<String>()
        coEvery { accountLookup.findAccountIdByIban(capture(ibanSlot)) } returns creditorId
        val reqSlot = slot<InitiateTransactionRequest>()
        every { client.initiateTransaction(capture(reqSlot)) } answers {
            Uni.createFrom().item(Response.status(201).entity(echo(reqSlot.captured)).build())
        }

        adapter().settle(p)

        // The constructed Czech IBAN: CZ + check + bank(0100) + 16-digit account(0000009876543210).
        assertThat(ibanSlot.captured).hasSize(24)
        assertThat(ibanSlot.captured).startsWith("CZ")
        assertThat(ibanSlot.captured.substring(4)).isEqualTo("01000000009876543210")
        // The payee is credited: the booking carries the resolved target account.
        assertThat(reqSlot.captured.targetAccountId).isEqualTo(creditorId)
        // An in-house transfer never traverses CERTIS, so it must not be labelled "CERTIS".
        assertThat(reqSlot.captured.description).startsWith("Interní převod")
    }

    @Test
    fun `settle books debit-only (no targetAccountId) for an external transfer`(): Unit = runBlocking {
        val p = payment().copy(transferScope = DomesticTransferScope.EXTERNAL)
        val reqSlot = slot<InitiateTransactionRequest>()
        every { client.initiateTransaction(capture(reqSlot)) } answers {
            Uni.createFrom().item(Response.status(201).entity(echo(reqSlot.captured)).build())
        }

        adapter().settle(p)

        assertThat(reqSlot.captured.targetAccountId).isNull()
        coVerify(exactly = 0) { accountLookup.findAccountIdByIban(any()) }
        // An interbank transfer settles through CERTIS, so it keeps the "CERTIS" prefix.
        assertThat(reqSlot.captured.description).startsWith("CERTIS")
    }

    private fun payment() = DomesticPayment(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        idempotencyKey = "settle-test",
        status = DomesticPaymentStatus.SENT_TO_CLEARING,
        debtorAccountId = debtor,
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Alice",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Bob",
        amount = Money.of(BigDecimal("500.00"), "CZK"),
        variableSymbol = "12345",
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = "Test payment",
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOM-E2E-001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
