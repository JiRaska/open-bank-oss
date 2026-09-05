// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.clearing.application.port.out.ClearingLedgerPostingPort
import com.openbank.clearing.application.port.out.NetSettlementPosting
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * ADR-0281: the consumer must post ONLY `net_settlement.post` commands (every other event on the
 * shared topic is acked), ack poison pills (a redelivery fails identically forever), and rethrow
 * a failing post so the connector dead-letters (#5698) rather than acking an unposted settlement.
 */
class NetSettlementPostingConsumerTest {

    private val postingPort = mockk<ClearingLedgerPostingPort>()
    private val consumer = NetSettlementPostingConsumer(ObjectMapper(), postingPort)

    private fun record(value: String): ConsumerRecord<String, String> =
        ConsumerRecord("openbank.clearing.batch.event", 0, 0L, "key", value)

    private val command = """
        {
          "eventType": "openbank.clearing.net_settlement.post",
          "sourceService": "clearing-service",
          "batchId": "11111111-1111-1111-1111-111111111111",
          "batchReference": "CYCLE-SEPA_SCT-20260904-1234",
          "cycleId": "CYCLE-SEPA_SCT-20260904-1234",
          "idempotencyKey": "clearing-net-settlement-11111111-1111-1111-1111-111111111111",
          "currency": "EUR",
          "settlementAmount": 100.50,
          "valueDate": "2026-09-04",
          "occurredAt": "2026-09-04T10:15:30Z"
        }
    """.trimIndent()

    @Test
    fun `a net_settlement post command is decoded and posted`(): Unit = runBlocking {
        val postingSlot = slot<NetSettlementPosting>()
        every { postingPort.postNetSettlement(capture(postingSlot)) } returns Uni.createFrom().item(Unit)

        consumer.consume(record(command))

        val posting = postingSlot.captured
        assertThat(posting.batchId).isEqualTo(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"))
        assertThat(posting.idempotencyKey)
            .isEqualTo("clearing-net-settlement-11111111-1111-1111-1111-111111111111")
        assertThat(posting.currency).isEqualTo("EUR")
        assertThat(posting.settlementAmount).isEqualByComparingTo("100.50")
        assertThat(posting.valueDate).isEqualTo(java.time.LocalDate.parse("2026-09-04"))
    }

    @Test
    fun `every other event type on the topic is acked without posting`(): Unit = runBlocking {
        consumer.consume(record("""{"eventType":"openbank.clearing.batch.settled","batchId":"x"}"""))
        consumer.consume(record("""{"eventType":"openbank.clearing.item.cleared","itemId":"x"}"""))

        verify(exactly = 0) { postingPort.postNetSettlement(any()) }
    }

    @Test
    fun `an unparseable body is acked as a poison pill`(): Unit = runBlocking {
        consumer.consume(record("this is not json"))
        consumer.consume(record("""{"eventType":"openbank.clearing.net_settlement.post","batchId":"not-a-uuid"}"""))

        verify(exactly = 0) { postingPort.postNetSettlement(any()) }
    }

    @Test
    fun `a failing post is rethrown so the connector dead-letters, never acked`(): Unit = runBlocking {
        every { postingPort.postNetSettlement(any()) } returns
            Uni.createFrom().failure(RuntimeException("ledger unreachable"))

        assertThatThrownBy {
            runBlocking { consumer.consume(record(command)) }
        }.hasMessageContaining("ledger unreachable")
    }
}
