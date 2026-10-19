// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.application.usecase.InstitutionTransferRefusedException
import com.openbank.swift.application.usecase.InstitutionTransferService
import com.openbank.swift.infrastructure.kafka.TreasuryPaymentReleasedConsumer
import io.mockk.coVerify
import io.mockk.mockk
import io.smallrye.config.source.yaml.YamlConfigSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

class TreasuryPaymentReleasedConsumerTest {
    private val service = mockk<InstitutionTransferService>(relaxed = true)
    private val consumer = TreasuryPaymentReleasedConsumer(service, ObjectMapper())

    @Test
    fun `non-JSON and non-object payloads are thrown for the DLQ, never acked`() {
        for (bad in listOf("not json", "[1,2]", "\"s\"")) {
            assertThatThrownBy {
                runBlocking { consumer.handle(bad) }
            }.isInstanceOf(InstitutionTransferRefusedException::class.java)
        }
        coVerify(exactly = 0) { service.onReleased(any()) }
    }

    @Test
    fun `a well-formed payload is parsed field by field and handed to the service`(): Unit = runBlocking {
        val m = InstitutionTransferFixtures.release(extra = mapOf("ssiVersion" to 4, "unknown" to true))
        consumer.handle(InstitutionTransferFixtures.json(m))
        val p = InstitutionTransferFixtures.parsed(m)
        assertThat(p.paymentId).isEqualTo(m["paymentId"])
        assertThat(p.debtorGlAccountingClass).isEqualTo("NOSTRO")
        assertThat(p.creditorName).isEqualTo("Counterparty Bank")
        assertThat(p.ssiVersion).isEqualTo(4L)
        assertThat(p.ssiVersionMalformed).isFalse()
        assertThat(p.synthetic).isTrue()
        coVerify(exactly = 1) { service.onReleased(p) }
    }

    @Test
    fun `wrong JSON types read as absent so validation refuses them`() {
        val p = InstitutionTransferFixtures.parsed(
            InstitutionTransferFixtures.release(
                extra = mapOf(
                    "amount" to 5,
                    "dealIds" to listOf(1),
                    "ssiVersion" to "x",
                ),
            ),
        )
        assertThat(p.amount).isNull()
        assertThat(p.dealIds).isNull()
        assertThat(p.ssiVersionMalformed).isTrue()
    }

    /** The real SmallRye YAML source must resolve what the Kafka connector reads (#5752). */
    @Test
    fun `the incoming channel dead-letters into its explicit nested topic`() {
        val cfg = YamlConfigSource("application.yaml", File("src/main/resources/application.yaml").readText())
        val c = "mp.messaging.incoming.treasury-payments-in"
        assertThat(cfg.getValue("$c.failure-strategy")).isEqualTo("dead-letter-queue")
        assertThat(
            cfg.getValue("$c.dead-letter-queue.topic"),
        ).isEqualTo("openbank.dlq.swift-service.treasury-payments-in")
        assertThat(cfg.getValue("$c.topic")).isEqualTo("openbank.treasury.payments")
        assertThat(
            cfg.getValue("mp.messaging.outgoing.swift-institution-transfers-out.topic"),
        ).isEqualTo("openbank.swift.institution-transfers")
        assertThat(cfg.propertyNames).doesNotContain("$c.\"dead-letter-queue.topic\"")
    }

    @Test
    fun `consume acks a handled record and nacks a refused one`(): Unit = runBlocking {
        var acked = 0
        var nacked = 0
        fun msg(p: String) = org.eclipse.microprofile.reactive.messaging.Message.of(p)
            .withAck {
                acked++
                java.util.concurrent.CompletableFuture.completedFuture(null)
            }
            .withNack {
                nacked++
                java.util.concurrent.CompletableFuture.completedFuture(null)
            }
        consumer.consume(msg("not json"))
        consumer.consume(msg(InstitutionTransferFixtures.json(InstitutionTransferFixtures.release())))
        assertThat(nacked).isEqualTo(1)
        assertThat(acked).isEqualTo(1)
    }
}
