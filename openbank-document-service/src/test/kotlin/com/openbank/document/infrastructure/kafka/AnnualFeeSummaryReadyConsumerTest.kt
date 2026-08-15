// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.AnnualFeeLine
import com.openbank.document.application.port.`in`.AnnualFeeSummaryReadyCommand
import com.openbank.document.application.port.`in`.AnnualStatementDeliveryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Poison-pill safety + field-presence guards for [AnnualFeeSummaryReadyConsumer] (mirrors AccountCreatedConsumerTest). */
class AnnualFeeSummaryReadyConsumerTest {

    private val deliveryUseCase: AnnualStatementDeliveryUseCase = mockk()
    private val consumer = AnnualFeeSummaryReadyConsumer(deliveryUseCase, ObjectMapper())

    @Test
    fun `delegates to the delivery use case for a well-formed AnnualFeeSummaryReady event`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { deliveryUseCase.deliverAnnualStatement(any()) } returns Unit

        consumer.consume(annualFeeSummaryPayload(accountId, partyId))

        coVerify(exactly = 1) {
            deliveryUseCase.deliverAnnualStatement(
                AnnualFeeSummaryReadyCommand(
                    accountId = accountId,
                    partyRef = partyId.toString(),
                    year = 2026,
                    currency = "CZK",
                    fees = listOf(
                        AnnualFeeLine(
                            name = "Account maintenance",
                            category = "MAINTENANCE",
                            amount = BigDecimal("120.00"),
                        ),
                    ),
                    totalFees = BigDecimal("120.00"),
                    interestRate = BigDecimal("0.50"),
                ),
            )
        }
    }

    @Test
    fun `treats a missing interestRate as null rather than skipping the event`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { deliveryUseCase.deliverAnnualStatement(any()) } returns Unit
        val payload = """
            {"eventType":"AnnualFeeSummaryReady","accountId":"$accountId","partyRef":"$partyId","year":2026,
             "currency":"CZK","fees":[],"totalFees":"0.00","occurredAt":"2026-01-01T00:00:00Z"}
        """.trimIndent()

        consumer.consume(payload)

        coVerify(exactly = 1) {
            deliveryUseCase.deliverAnnualStatement(
                AnnualFeeSummaryReadyCommand(
                    accountId,
                    partyId.toString(),
                    2026,
                    "CZK",
                    emptyList(),
                    BigDecimal("0.00"),
                    null,
                ),
            )
        }
    }

    @Test
    fun `ignores an event of a different type`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"AnnualFeeSummaryFailed","accountId":"${UUID.randomUUID()}"}""")

        coVerify(exactly = 0) { deliveryUseCase.deliverAnnualStatement(any()) }
    }

    @Test
    fun `ignores an unparseable payload instead of throwing`(): Unit = runBlocking {
        consumer.consume("not json at all {{{")

        coVerify(exactly = 0) { deliveryUseCase.deliverAnnualStatement(any()) }
    }

    @Test
    fun `ignores an AnnualFeeSummaryReady event missing totalFees`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val payload = """
            {"eventType":"AnnualFeeSummaryReady","accountId":"$accountId","partyRef":"$partyId","year":2026,
             "currency":"CZK","fees":[]}
        """.trimIndent()

        consumer.consume(payload)

        coVerify(exactly = 0) { deliveryUseCase.deliverAnnualStatement(any()) }
    }

    @Test
    fun `drops a fee line with an unparseable amount but keeps the rest of the event`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { deliveryUseCase.deliverAnnualStatement(any()) } returns Unit
        val payload = """
            {"eventType":"AnnualFeeSummaryReady","accountId":"$accountId","partyRef":"$partyId","year":2026,
             "currency":"CZK","fees":[
               {"code":"BAD","name":"Bad line","category":"X","amount":"not-a-number"},
               {"code":"OK","name":"Good line","category":"Y","amount":"10.00"}
             ],"totalFees":"10.00"}
        """.trimIndent()

        consumer.consume(payload)

        coVerify(exactly = 1) {
            deliveryUseCase.deliverAnnualStatement(
                AnnualFeeSummaryReadyCommand(
                    accountId,
                    partyId.toString(),
                    2026,
                    "CZK",
                    listOf(AnnualFeeLine(name = "Good line", category = "Y", amount = BigDecimal("10.00"))),
                    BigDecimal("10.00"),
                    null,
                ),
            )
        }
    }

    @Test
    fun `swallows a downstream failure so one bad account never wedges the consumer`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { deliveryUseCase.deliverAnnualStatement(any()) } throws
            IllegalStateException("No PUBLISHED ROCNI_VYPIS_POPLATKU_CS template")

        // consume() must NOT propagate — a deterministic downstream failure for one account must not
        // halt the stream (poison-pill safety). runBlocking would rethrow if consume() let it escape.
        consumer.consume(annualFeeSummaryPayload(accountId, partyId))

        coVerify(exactly = 1) { deliveryUseCase.deliverAnnualStatement(any()) }
    }

    private fun annualFeeSummaryPayload(accountId: UUID, partyId: UUID) = """
        {"eventType":"AnnualFeeSummaryReady","accountId":"$accountId","partyRef":"$partyId","year":2026,
         "currency":"CZK","fees":[{"code":"MAINT","name":"Account maintenance","category":"MAINTENANCE","amount":"120.00"}],
         "totalFees":"120.00","interestRate":"0.50","occurredAt":"2026-01-01T00:00:00Z"}
    """.trimIndent()
}
