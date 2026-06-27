// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SchemeAcceptedConsumerTest {

    private val transactionUseCase = mockk<TransactionUseCase>(relaxed = true)
    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private lateinit var consumer: SchemeAcceptedConsumer

    @BeforeEach
    fun setUp() {
        consumer = SchemeAcceptedConsumer(transactionUseCase, objectMapper)
    }

    @Test
    fun `maps SchemeAcceptedEvent to InitiateTransactionCommand with correct fields`() {
        val paymentId = UUID.randomUUID()
        val debtorAccountId = UUID.randomUUID()
        val event = SchemeAcceptedEvent(
            paymentId = paymentId,
            debtorAccountId = debtorAccountId,
            creditorAccountId = null,
            debtorIban = "CZ6508000000192000145399",
            creditorIban = "DE91100000000123456789",
            amount = BigDecimal("250.00"),
            currency = "CZK",
            valueDate = LocalDate.of(2026, 6, 23),
            rail = PaymentRail.SEPA_CT,
        )
        val payload = objectMapper.writeValueAsString(event)
        val commandSlot = slot<InitiateTransactionCommand>()
        coEvery { transactionUseCase.initiateTransaction(capture(commandSlot)) } returns mockk(relaxed = true)

        consumer.onSchemeAccepted(payload)

        val cmd = commandSlot.captured
        assertThat(cmd.idempotencyKey).isEqualTo(paymentId.toString())
        assertThat(cmd.type).isEqualTo(TransactionType.DEBIT)
        assertThat(cmd.sourceAccountId).isEqualTo(debtorAccountId)
        assertThat(cmd.targetAccountId).isNull()
        assertThat(cmd.amount).isEqualByComparingTo(BigDecimal("250.00"))
        assertThat(cmd.currencyCode).isEqualTo("CZK")
        assertThat(cmd.valueDate).isEqualTo(LocalDate.of(2026, 6, 23))
        assertThat(cmd.originatingPaymentId).isEqualTo(paymentId)
        assertThat(cmd.rail).isEqualTo(PaymentRail.SEPA_CT)
    }

    @Test
    fun `idempotency — same paymentId produces same idempotencyKey on both calls`() {
        val paymentId = UUID.randomUUID()
        val event = SchemeAcceptedEvent(
            paymentId = paymentId,
            debtorAccountId = UUID.randomUUID(),
            creditorAccountId = null,
            debtorIban = "CZ6508000000192000145399",
            creditorIban = "DE91100000000123456789",
            amount = BigDecimal("100.00"),
            currency = "EUR",
            valueDate = LocalDate.now(),
            rail = PaymentRail.SEPA_CT,
        )
        val payload = objectMapper.writeValueAsString(event)
        val captured = mutableListOf<InitiateTransactionCommand>()
        coEvery { transactionUseCase.initiateTransaction(capture(captured)) } returns mockk(relaxed = true)

        consumer.onSchemeAccepted(payload)
        consumer.onSchemeAccepted(payload)

        assertThat(captured).hasSize(2)
        assertThat(captured[0].idempotencyKey).isEqualTo(paymentId.toString())
        assertThat(captured[1].idempotencyKey).isEqualTo(paymentId.toString())
    }

    @Test
    fun `malformed JSON is dropped without throwing`() {
        coEvery { transactionUseCase.initiateTransaction(any()) } returns mockk(relaxed = true)

        consumer.onSchemeAccepted("not-json")

        coVerify(exactly = 0) { transactionUseCase.initiateTransaction(any()) }
    }

    @Test
    fun `exception from initiateTransaction propagates — SmallRye routes to DLQ`() {
        // When transactionUseCase throws, the exception must escape onSchemeAccepted
        // so SmallRye Reactive Messaging's failure-strategy=dead-letter-queue can catch
        // it and route the message to payment.scheme-accepted.dlq. A silent swallow here
        // would mean failed settlements are lost without any DLQ record.
        val event = SchemeAcceptedEvent(
            paymentId = UUID.randomUUID(),
            debtorAccountId = UUID.randomUUID(),
            creditorAccountId = null,
            debtorIban = "CZ6508000000192000145399",
            creditorIban = "DE91100000000123456789",
            amount = BigDecimal("100.00"),
            currency = "CZK",
            valueDate = LocalDate.now(),
            rail = PaymentRail.SEPA_CT,
        )
        val payload = objectMapper.writeValueAsString(event)
        coEvery { transactionUseCase.initiateTransaction(any()) } throws RuntimeException("ledger unavailable")

        assertThrows<RuntimeException> { consumer.onSchemeAccepted(payload) }
    }
}
