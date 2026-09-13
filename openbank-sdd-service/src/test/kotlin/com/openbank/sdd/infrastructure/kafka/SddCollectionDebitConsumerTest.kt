// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.sdd.infrastructure.client.InitiateTransactionRequest
import com.openbank.sdd.infrastructure.client.TransactionServiceClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class SddCollectionDebitConsumerTest {

    private val mapper = jacksonObjectMapper()
    private val transactionClient = mockk<TransactionServiceClient>()
    private val consumer = SddCollectionDebitConsumer(mapper, transactionClient)

    private val mandateId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val accountId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

    private fun collectionAuthorisedEvent(
        eventType: String = "sdd.collection.authorised.v1",
        amount: String = "2200.00",
        currency: String = "EUR",
    ): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to eventType,
            "mandateId" to mandateId,
            "accountId" to accountId,
            "debtorIban" to "DE89370400440532013001",
            "creditorIdentifier" to "DE98ZZZ09999999999",
            "umr" to "UMR-2026-001",
            "scheme" to "CORE",
            "sequenceType" to "RCUR",
            "amount" to amount.toBigDecimal(),
            "currency" to currency,
            "dueDate" to "2026-07-13",
            "occurredAt" to "2026-07-13T10:00:00Z",
        ),
    )

    @Test
    fun `books the debtor debit for a collection-authorised event and swallows a 2xx as success`(): Unit = runBlocking {
        val req = slot<InitiateTransactionRequest>()
        every { transactionClient.initiateTransaction(capture(req)) } returns
            Uni.createFrom().item(Response.status(201).build())

        consumer.consume(collectionAuthorisedEvent())

        assertThat(req.captured.idempotencyKey).isEqualTo("so-sdd-$mandateId-UMR-2026-001-2026-07-13")
        assertThat(req.captured.type).isEqualTo("DEBIT")
        assertThat(req.captured.sourceAccountId).isEqualTo(accountId)
        assertThat(req.captured.amount).isEqualByComparingTo("2200.00")
        assertThat(req.captured.currencyCode).isEqualTo("EUR")
        assertThat(req.captured.rail).isEqualTo("SEPA_CT")
        assertThat(req.captured.instructionType).isEqualTo("DIRECT_DEBIT")
    }

    @Test
    fun `a 409 from transaction-service is treated as an idempotent success, no error logged`(): Unit = runBlocking {
        every { transactionClient.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(409).build())

        // Must not throw — the poison-pill boundary swallows nothing here because 409 is a
        // recognized, non-exceptional outcome.
        consumer.consume(collectionAuthorisedEvent())

        verify(exactly = 1) { transactionClient.initiateTransaction(any()) }
    }

    @Test
    fun `a non-2xx-non-409 response is retried and then rethrown so the connector dead-letters`(): Unit = runBlocking {
        // This test previously asserted the OPPOSITE — "handled without throwing (logged as a
        // failure)" — and passing was exactly the problem (#5698): returning normally ACKS the
        // message, so an authorised collection that never debited was indistinguishable from one
        // that did. R-transaction/return generation is not built (#1000), so the DLQ is the only
        // path by which a human learns the money did not move.
        every { transactionClient.initiateTransaction(any()) } returns
            Uni.createFrom().item(Response.status(500).build())

        assertThatThrownBy { runBlocking { consumer.consume(collectionAuthorisedEvent()) } }
            .isInstanceOf(SddDebitFailedException::class.java)
            .hasMessageContaining("did not move money")

        // Bounded, not unbounded: a permanently refusing rail must not pin the partition forever.
        verify(exactly = EventRetry.DEFAULT_MAX_ATTEMPTS) { transactionClient.initiateTransaction(any()) }
    }

    @Test
    fun `a mandate lifecycle event (not collection-authorised) is ignored`(): Unit = runBlocking {
        consumer.consume(collectionAuthorisedEvent(eventType = "sdd.mandate.amended.v1"))

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
    }

    @Test
    fun `a malformed payload is swallowed, not thrown`(): Unit = runBlocking {
        consumer.consume("{bad-json")

        verify(exactly = 0) { transactionClient.initiateTransaction(any()) }
    }

    @Test
    fun `amount scale is normalised to the currency's fraction digits`(): Unit = runBlocking {
        val req = slot<InitiateTransactionRequest>()
        every { transactionClient.initiateTransaction(capture(req)) } returns
            Uni.createFrom().item(Response.status(201).build())

        consumer.consume(collectionAuthorisedEvent(amount = "2200.000000"))

        assertThat(req.captured.amount.scale()).isEqualTo(2)
        assertThat(req.captured.amount).isEqualByComparingTo("2200.00")
    }
}
