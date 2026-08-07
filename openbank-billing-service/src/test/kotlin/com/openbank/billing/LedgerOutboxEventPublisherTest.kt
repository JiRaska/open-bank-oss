// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.LedgerPostingPort
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import com.openbank.billing.infrastructure.outbox.LedgerOutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private fun noOpEmitter(): MutinyEmitter<String> {
    val emitter = mockk<MutinyEmitter<String>>()
    every { emitter.sendMessage(any<Message<String>>()) } returns Uni.createFrom().voidItem()
    return emitter
}

/**
 * Unit coverage for [LedgerOutboxEventPublisher] (ADR-0143 step 2): "publishing" an outbox row
 * means calling the ledger — not Kafka — and on success the fee is marked POSTED with the
 * returned journal id.
 */
class LedgerOutboxEventPublisherTest {

    private fun entry(payload: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "billing.fee.post-intent.v1",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    private fun payload(idempotencyKey: String = "fee-2026-07-acc-1-f1-CZK") =
        "{\"schemaVersion\":1,\"idempotencyKey\":\"$idempotencyKey\",\"cycleId\":\"2026-07\"," +
            "\"accountId\":\"acc-1\",\"feeId\":\"f1\",\"amount\":\"150.00\",\"currency\":\"CZK\"," +
            "\"description\":\"Fee charge: Maintenance\"}"

    @Test
    fun `publish posts the deserialized command to the ledger and marks the fee POSTED with the journal id`(): Unit =
        runBlocking {
            val ledger = mockk<LedgerPostingPort>()
            val assessments = mockk<BillingAssessmentRepository>()
            val journalId = UUID.randomUUID()
            val commandSlot: CapturingSlot<FeeJournalCommand> = slot()
            coEvery { ledger.post(capture(commandSlot)) } returns journalId
            coEvery { assessments.markPosted("fee-2026-07-acc-1-f1-CZK", journalId) } returns Unit

            LedgerOutboxEventPublisher(ledger, assessments, noOpEmitter()).publish(entry(payload()))

            coVerify(exactly = 1) { ledger.post(any()) }
            coVerify(exactly = 1) { assessments.markPosted("fee-2026-07-acc-1-f1-CZK", journalId) }
            val command = commandSlot.captured
            assertThat(command.idempotencyKey).isEqualTo("fee-2026-07-acc-1-f1-CZK")
            assertThat(command.cycleId).isEqualTo("2026-07")
            assertThat(command.accountId).isEqualTo("acc-1")
            assertThat(command.feeId).isEqualTo("f1")
            assertThat(command.currency).isEqualTo("CZK")
        }

    @Test
    fun `a ledger failure propagates and does not mark the fee posted`() {
        val ledger = mockk<LedgerPostingPort>()
        val assessments = mockk<BillingAssessmentRepository>()
        coEvery { ledger.post(any()) } throws RuntimeException("ledger down")

        assertThatThrownBy {
            runBlocking { LedgerOutboxEventPublisher(ledger, assessments, noOpEmitter()).publish(entry(payload())) }
        }.isInstanceOf(RuntimeException::class.java).hasMessageContaining("ledger down")

        coVerify(exactly = 0) { assessments.markPosted(any(), any()) }
    }

    private fun reversalEntry(payload: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "billing.fee.reversal-intent.v1",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    private fun reversalPayload(
        idempotencyKey: String = "fee-reversal-2026-07-acc-1-f1-CZK",
        originalIdempotencyKey: String = "fee-2026-07-acc-1-f1-CZK",
    ) = "{\"schemaVersion\":1,\"idempotencyKey\":\"$idempotencyKey\"," +
        "\"originalIdempotencyKey\":\"$originalIdempotencyKey\",\"cycleId\":\"2026-07\"," +
        "\"accountId\":\"acc-1\",\"feeId\":\"f1\",\"amount\":\"150.00\",\"currency\":\"CZK\"," +
        "\"reason\":\"waiver bug\"}"

    @Test
    fun `publishing a reversal-intent row posts the compensating journal and marks the ORIGINAL fee REVERSED`(): Unit =
        runBlocking {
            val ledger = mockk<LedgerPostingPort>()
            val assessments = mockk<BillingAssessmentRepository>()
            val reversalJournalId = UUID.randomUUID()
            val commandSlot: CapturingSlot<FeeReversalCommand> = slot()
            coEvery { ledger.postReversal(capture(commandSlot)) } returns reversalJournalId
            coEvery {
                assessments.markReversed("fee-2026-07-acc-1-f1-CZK", reversalJournalId)
            } returns Unit

            LedgerOutboxEventPublisher(ledger, assessments, noOpEmitter()).publish(reversalEntry(reversalPayload()))

            coVerify(exactly = 1) { ledger.postReversal(any()) }
            coVerify(exactly = 1) { assessments.markReversed("fee-2026-07-acc-1-f1-CZK", reversalJournalId) }
            coVerify(exactly = 0) { ledger.post(any()) }
            coVerify(exactly = 0) { assessments.markPosted(any(), any()) }
            val command = commandSlot.captured
            assertThat(command.idempotencyKey).isEqualTo("fee-reversal-2026-07-acc-1-f1-CZK")
            assertThat(command.originalIdempotencyKey).isEqualTo("fee-2026-07-acc-1-f1-CZK")
            assertThat(command.reason).isEqualTo("waiver bug")
        }

    @Test
    fun `a reversal ledger failure propagates and does not mark the fee reversed`() {
        val ledger = mockk<LedgerPostingPort>()
        val assessments = mockk<BillingAssessmentRepository>()
        coEvery { ledger.postReversal(any()) } throws RuntimeException("ledger down")

        assertThatThrownBy {
            runBlocking {
                LedgerOutboxEventPublisher(ledger, assessments, noOpEmitter()).publish(reversalEntry(reversalPayload()))
            }
        }.isInstanceOf(RuntimeException::class.java).hasMessageContaining("ledger down")

        coVerify(exactly = 0) { assessments.markReversed(any(), any()) }
    }

    private fun annualSummaryEntry(payload: String) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "billing.annual-fee-summary.ready",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `publishing an annual-fee-summary row relays the payload to Kafka, never the ledger`(): Unit = runBlocking {
        val ledger = mockk<LedgerPostingPort>()
        val assessments = mockk<BillingAssessmentRepository>()
        val emitter = mockk<MutinyEmitter<String>>()
        val messageSlot: CapturingSlot<Message<String>> = slot()
        every { emitter.sendMessage(capture(messageSlot)) } returns Uni.createFrom().voidItem()
        val payload = "{\"eventType\":\"AnnualFeeSummaryReady\",\"accountId\":\"acc-1\"}"

        LedgerOutboxEventPublisher(ledger, assessments, emitter).publish(annualSummaryEntry(payload))

        coVerify(exactly = 0) { ledger.post(any()) }
        coVerify(exactly = 0) { ledger.postReversal(any()) }
        coVerify(exactly = 0) { assessments.markPosted(any(), any()) }
        coVerify(exactly = 0) { assessments.markReversed(any(), any()) }
        assertThat(messageSlot.captured.payload).isEqualTo(payload)
    }
}
