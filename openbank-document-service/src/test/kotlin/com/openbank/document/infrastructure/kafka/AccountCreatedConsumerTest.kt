// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.IssueOnboardingDocumentCommand
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Named so detekt's TooGenericExceptionThrown does not fire at the throw site. */
private class TransientDbFailure : RuntimeException("connection refused")

/** Poison-pill safety + field-presence guards for [AccountCreatedConsumer] (mirrors BalanceInitConsumer). */
class AccountCreatedConsumerTest {

    private val onboardingUseCase: OnboardingDocumentUseCase = mockk()
    private val consumer = AccountCreatedConsumer(onboardingUseCase, ObjectMapper())

    @Test
    fun `delegates to the onboarding use case for a well-formed AccountCreated event`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        coEvery { onboardingUseCase.issueOnboardingDocument(any()) } returns Unit

        consumer.consume(accountCreatedPayload(accountId, partyId, productId))

        coVerify(exactly = 1) {
            onboardingUseCase.issueOnboardingDocument(
                IssueOnboardingDocumentCommand(accountId, partyId.toString(), productId),
            )
        }
    }

    @Test
    fun `ignores an event of a different type`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"AccountClosed","aggregateId":"${UUID.randomUUID()}"}""")

        coVerify(exactly = 0) { onboardingUseCase.issueOnboardingDocument(any()) }
    }

    @Test
    fun `ignores an unparseable payload instead of throwing`(): Unit = runBlocking {
        consumer.consume("not json at all {{{")

        coVerify(exactly = 0) { onboardingUseCase.issueOnboardingDocument(any()) }
    }

    @Test
    fun `ignores an AccountCreated event missing productId`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val payload = """{"eventType":"AccountCreated","aggregateId":"$accountId","partyId":"$partyId"}"""

        consumer.consume(payload)

        coVerify(exactly = 0) { onboardingUseCase.issueOnboardingDocument(any()) }
    }

    @Test
    fun `acks a DETERMINISTIC downstream failure so one bad account never wedges the consumer`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        coEvery { onboardingUseCase.issueOnboardingDocument(any()) } throws
            IllegalStateException("No published template for the product's documentTemplateCode")

        // consume() must NOT propagate — a deterministic downstream failure for one account must not
        // halt the stream (poison-pill safety). runBlocking would rethrow if consume() let it escape.
        consumer.consume(accountCreatedPayload(accountId, partyId, productId))

        // Exactly once: a failure that fails identically on every delivery must not burn retries.
        coVerify(exactly = 1) { onboardingUseCase.issueOnboardingDocument(any()) }
    }

    /**
     * The other half of #5698, and the reason the test above is not enough on its own. The original
     * catch was `catch (e: Exception)`, so a connection-refused from Postgres was acked exactly like
     * the no-template case — an event that did no work, indistinguishable from one that succeeded.
     */
    @Test
    fun `a TRANSIENT downstream failure is retried and then RETHROWN so the connector dead-letters`(): Unit =
        runBlocking {
            val accountId = UUID.randomUUID()
            val partyId = UUID.randomUUID()
            val productId = UUID.randomUUID()
            coEvery { onboardingUseCase.issueOnboardingDocument(any()) } throws TransientDbFailure()

            assertThrows<TransientDbFailure> {
                runBlocking { consumer.consume(accountCreatedPayload(accountId, partyId, productId)) }
            }

            coVerify(exactly = 3) { onboardingUseCase.issueOnboardingDocument(any()) }
        }

    @Test
    fun `a transient failure that recovers is retried to success, not dead-lettered`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        var calls = 0
        coEvery { onboardingUseCase.issueOnboardingDocument(any()) } answers {
            calls++
            if (calls == 1) throw TransientDbFailure() else Unit
        }

        consumer.consume(accountCreatedPayload(accountId, partyId, productId))

        coVerify(exactly = 2) { onboardingUseCase.issueOnboardingDocument(any()) }
    }

    private fun accountCreatedPayload(accountId: UUID, partyId: UUID, productId: UUID) = """
        {"eventType":"AccountCreated","aggregateId":"$accountId","partyId":"$partyId","productId":"$productId","currency":"CZK"}
    """.trimIndent()
}
