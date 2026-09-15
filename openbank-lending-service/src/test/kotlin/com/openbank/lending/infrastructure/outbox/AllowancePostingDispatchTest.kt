// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.outbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AllowancePostingDispatchTest {
    private val emitter = mockk<MutinyEmitter<String>>()
    private val ledger = mockk<LedgerPostingPort>()
    private val publisher = KafkaLendingOutboxEventPublisher(
        emitter,
        ledger,
        jacksonObjectMapper().findAndRegisterModules(),
    )
    private val loanId = UUID.fromString("77777777-7777-7777-7777-777777777777")
    private val partyId = UUID.fromString("88888888-8888-8888-8888-888888888888")

    private fun command() = OutboxEntry(
        eventId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
        aggregateId = loanId,
        eventType = "lending.allowance.posting",
        payload = """{"reference":"loan:$loanId:allowance:release","partyId":"$partyId","loanId":"$loanId",""" +
            """"amount":-900.00,"currency":"EUR","accountingDate":"2026-04-01"}""",
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-04-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-01T10:00:00Z"),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `allowance retries preserve amount reference and accounting date without publishing to Kafka`(): Unit =
        runBlocking {
            val postings = mutableListOf<LedgerPosting>()
            every { ledger.post(capture(postings)) } returns Uni.createFrom().item(Unit)
            val entry = command()

            publisher.publish(entry)
            publisher.publish(entry.copy(attemptCount = 1, updatedAt = Instant.parse("2026-04-03T10:00:00Z")))

            assertThat(postings).containsExactly(
                LedgerPosting(
                    "loan:$loanId:allowance:release",
                    partyId,
                    Money.of("-900.00", "EUR"),
                    PostingKind.PROVISIONING,
                    LocalDate.parse("2026-04-01"),
                ),
                postings.first(),
            )
            verify(exactly = 0) { emitter.sendMessage(any()) }
        }

    @Test
    fun `ledger failure propagates so the command remains retryable`(): Unit = runBlocking {
        val failure = IllegalStateException("ledger unavailable")
        every { ledger.post(any()) } returns Uni.createFrom().failure(failure)

        val result = runCatching { publisher.publish(command()) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            .hasMessage("ledger unavailable")
        verify(exactly = 0) { emitter.sendMessage(any()) }
    }

    @Test
    fun `ordinary domain events retain the Kafka route`(): Unit = runBlocking {
        every { emitter.sendMessage(any()) } returns Uni.createFrom().voidItem()

        publisher.publish(command().copy(eventType = "loan.provisioned", payload = "{}"))

        verify(exactly = 1) { emitter.sendMessage(match { it.payload == "{}" }) }
        verify(exactly = 0) { ledger.post(any()) }
    }

    @Test
    fun `provisioned evidence waits for the ledger acknowledgement`(): Unit = runBlocking {
        val order = mutableListOf<String>()
        every { ledger.post(any()) } answers {
            order += "ledger"
            Uni.createFrom().item(Unit)
        }
        every { emitter.sendMessage(any()) } answers {
            order += "event"
            Uni.createFrom().voidItem()
        }
        val tree = jacksonObjectMapper().readTree(command().payload) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("eventPayload", "{\"eventType\":\"loan.provisioned\"}")
        publisher.publish(command().copy(payload = tree.toString()))
        assertThat(order).containsExactly("ledger", "event")
    }
}
