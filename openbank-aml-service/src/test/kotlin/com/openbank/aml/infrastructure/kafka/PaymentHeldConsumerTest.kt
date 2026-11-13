// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.out.AccountPartyPort
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.config.source.yaml.YamlConfigSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class PaymentHeldConsumerTest {
    private val cases: AmlCaseUseCase = mockk()
    private val accountParty: AccountPartyPort = mockk()
    private val meters = SimpleMeterRegistry()
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val consumer = PaymentHeldConsumer(cases, accountParty, mapper, meters)

    private val paymentId = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val party = UUID.randomUUID()
    private val command = slot<CreateAmlCaseCommand>()

    private fun held(overrides: Map<String, Any?> = emptyMap()): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to "PAYMENT_HELD",
            "paymentId" to paymentId,
            "rail" to "DOMESTIC",
            "caseKey" to "payment-hold:domestic:$paymentId",
            "debtorAccountId" to account,
            "debtorPartyId" to party,
            "customerReference" to "1234567890/0800",
            "reasons" to listOf("SANCTIONS_POTENTIAL_MATCH"),
            "steps" to listOf(
                mapOf("step" to "STANDING", "outcome" to "DECIDED", "decision" to "ALLOW", "reason" to "STANDING_GOOD"),
                mapOf(
                    "step" to "SANCTIONS",
                    "outcome" to "DECIDED",
                    "decision" to "HOLD",
                    "reason" to "SANCTIONS_POTENTIAL_MATCH",
                    "evidence" to mapOf("creditor_status" to "POTENTIAL_HIT", "creditor_matched_entity" to "Listed 7"),
                ),
                mapOf("step" to "LIMITS", "outcome" to "NOT_APPLICABLE", "reason" to "LIMITS_NOT_DELIVERED"),
            ),
            "expiresAt" to "2026-11-15T10:00:00Z",
        ) + overrides,
    )

    private fun opened() {
        coEvery { cases.createCase(capture(command)) } returns mockk<AmlCase>(relaxed = true)
    }

    private fun outcome(name: String) = meters.find(PaymentHeldConsumer.METRIC).tag("outcome", name).counter()?.count()

    @Test
    fun `a held payment opens one case keyed and coded so that only it can decide the hold`(): Unit = runBlocking {
        opened()

        consumer.consume(held())

        with(command.captured) {
            assertThat(idempotencyKey).isEqualTo("payment-hold:domestic:$paymentId")
            assertThat(alertCode).isEqualTo("PAYMENT_HELD")
            assertThat(transactionId).isEqualTo(paymentId)
            assertThat(partyId).isEqualTo(party)
            assertThat(accountId).isEqualTo(account)
            assertThat(screeningType).isEqualTo(ScreeningType.TRANSACTION_MONITORING)
            assertThat(riskLevel).isEqualTo(AmlRiskLevel.HIGH)
            assertThat(customerReference).isEqualTo("1234567890/0800")
            assertThat(matchedEntity).isEqualTo("Listed 7")
            assertThat(
                alertDetail,
            ).contains("SANCTIONS_POTENTIAL_MATCH", "creditor_status=POTENTIAL_HIT", "2026-11-15T10:00:00Z")
                .doesNotContain("LIMITS")
        }
        assertThat(outcome("case_opened")).isEqualTo(1.0)
        coVerify(exactly = 0) { accountParty.findPartyByAccountId(any()) }
    }

    @Test
    fun `a hold that could not name the party is resolved here, and an unresolvable one is not acked`() {
        opened()
        coEvery { accountParty.findPartyByAccountId(account) } returns party
        runBlocking { consumer.consume(held(mapOf("debtorPartyId" to null))) }
        assertThat(command.captured.partyId).isEqualTo(party)

        coEvery { accountParty.findPartyByAccountId(account) } returns null
        assertThatThrownBy { runBlocking { consumer.consume(held(mapOf("debtorPartyId" to null))) } }
            .isInstanceOf(HeldPaymentPartyUnresolvedException::class.java)
        coVerify(exactly = 1) { cases.createCase(any()) }
    }

    @Test
    fun `every other event on a rail's topic is ignored without a case`(): Unit = runBlocking {
        consumer.consume(
            """{"eventType":"DOMESTIC_PAYMENT_STATUS_CHANGED","paymentId":"$paymentId","newStatus":"HELD"}""",
        )
        consumer.consume("""{"eventType":"DOMESTIC_PAYMENT_CREATED","paymentId":"$paymentId"}""")
        consumer.consume("""{"paymentId":"$paymentId"}""")

        coVerify(exactly = 0) { cases.createCase(any()) }
        assertThat(outcome("ignored")).isEqualTo(3.0)
    }

    @Test
    fun `a producer does not choose the case key`() {
        for (key in listOf(
            "tm:${UUID.randomUUID()}",
            "payment-hold:domestic:${UUID.randomUUID()}",
            "payment-hold:sepa:$paymentId",
            "",
        )) {
            assertThatThrownBy { runBlocking { consumer.consume(held(mapOf("caseKey" to key))) } }
                .describedAs("caseKey '%s'", key)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        coVerify(exactly = 0) { cases.createCase(any()) }
        assertThat(outcome("rejected")).isEqualTo(4.0)
    }

    @Test
    fun `an unreadable held-payment event is rethrown for the dead-letter queue`() {
        val broken = listOf(
            "{not json",
            "[]",
            held(mapOf("paymentId" to "not-a-uuid")),
            held(mapOf("rail" to "domestic; drop")),
            held(mapOf("debtorAccountId" to null)),
            held(mapOf("customerReference" to " ")),
        )
        broken.forEach { payload ->
            assertThatThrownBy { runBlocking { consumer.consume(payload) } }
                .describedAs(payload.take(60))
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        coVerify(exactly = 0) { cases.createCase(any()) }
    }

    @Test
    fun `a case store failure is not acked`() {
        coEvery { cases.createCase(any()) } throws IllegalStateException("aml-db is down")

        assertThatThrownBy { runBlocking { consumer.consume(held()) } }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the channel is off by default and reads the hold from the start of the topic`() {
        val config = YamlConfigSource("application.yaml", File("src/main/resources/application.yaml").readText())
        val prefix = "mp.messaging.incoming.${PaymentHeldConsumer.CHANNEL}"
        assertThat(config.getValue("$prefix.enabled")).isEqualTo("\${AML_PAYMENT_HELD_CONSUMER_ENABLED:false}")
        assertThat(config.getValue("$prefix.auto.offset.reset")).isEqualTo("earliest")
        assertThat(config.getValue("$prefix.topic")).isEqualTo("openbank.domestic.payment.events")
    }
}
