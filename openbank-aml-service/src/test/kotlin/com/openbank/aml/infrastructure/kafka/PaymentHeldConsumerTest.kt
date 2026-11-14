// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.CaseOrigin
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
    private val meters = SimpleMeterRegistry()
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val consumer = PaymentHeldConsumer(cases, mapper, meters)

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
            assertThat(origin).isEqualTo(CaseOrigin.PAYMENT_HOLD_INTAKE)
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
    }

    @Test
    fun `a hold that could not name the party opens its case with the party left unset`(): Unit = runBlocking {
        opened()

        // Both shapes a rail may send for "not known": an explicit null and no field at all.
        consumer.consume(held(mapOf("debtorPartyId" to null)))
        assertThat(command.captured.partyId).isNull()
        consumer.consume(held().replace(""","debtorPartyId":"$party"""", ""))
        assertThat(command.captured.partyId).isNull()

        // Not a made-up party and not the account filed as one; the account stays where it belongs.
        assertThat(command.captured.accountId).isEqualTo(account)
        assertThat(command.captured.origin).isEqualTo(CaseOrigin.PAYMENT_HOLD_INTAKE)
        coVerify(exactly = 2) { cases.createCase(any()) }
        assertThat(outcome("case_opened")).isEqualTo(2.0)
    }

    @Test
    fun `opening a hold's case depends on the case store alone`() {
        // The account authority is not something this class can reach: were a lookup reintroduced,
        // a hold would again be dead-lettered exactly when that authority is down.
        assertThat(PaymentHeldConsumer::class.java.declaredConstructors.single().parameterTypes.map { it.simpleName })
            .containsExactly("AmlCaseUseCase", "ObjectMapper", "MeterRegistry")
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

    /**
     * A decision covers the reasons its case was opened for. When a rail finds a new reason at
     * release it keeps the payment held and asks for another review, and that review is a case of
     * its own: `review` 2, 3, … under the hold key with the review number appended.
     */
    @Test
    fun `a later review of the same hold opens its own case under its own key`(): Unit = runBlocking {
        opened()

        consumer.consume(held(mapOf("review" to 2, "caseKey" to "payment-hold:domestic:$paymentId:2")))

        assertThat(command.captured.idempotencyKey).isEqualTo("payment-hold:domestic:$paymentId:2")
        assertThat(command.captured.transactionId).isEqualTo(paymentId)
        assertThat(command.captured.origin).isEqualTo(CaseOrigin.PAYMENT_HOLD_INTAKE)
        assertThat(command.captured.alertCode).isEqualTo("PAYMENT_HELD")
        assertThat(
            PaymentHeldConsumer.caseKey("DOMESTIC", paymentId, 17),
        ).isEqualTo("payment-hold:domestic:$paymentId:17")
        assertThat(PaymentHeldConsumer.caseKey("DOMESTIC", paymentId, 1)).isEqualTo("payment-hold:domestic:$paymentId")
    }

    @Test
    fun `no review and review 1 are the first review, under the key a hold has always had`(): Unit = runBlocking {
        opened()
        val first = "payment-hold:domestic:$paymentId"

        consumer.consume(held())
        assertThat(command.captured.idempotencyKey).isEqualTo(first)
        consumer.consume(held(mapOf("review" to 1)))
        assertThat(command.captured.idempotencyKey).isEqualTo(first)
        consumer.consume(held(mapOf("review" to null)))
        assertThat(command.captured.idempotencyKey).isEqualTo(first)
        assertThat(PaymentHeldConsumer.caseKey("DOMESTIC", paymentId)).isEqualTo(first)
    }

    @Test
    fun `a review does not get another review's key, and a review that is not a count is unreadable`() {
        val first = "payment-hold:domestic:$paymentId"
        val refused = listOf(
            // The key of the first review, claimed by the second: its decision would stand for both.
            mapOf("review" to 2, "caseKey" to first),
            mapOf("review" to 2, "caseKey" to "$first:3"),
            mapOf("review" to 1, "caseKey" to "$first:1"),
            mapOf("caseKey" to "$first:2"),
            mapOf("review" to 0),
            mapOf("review" to -1, "caseKey" to "$first:-1"),
            mapOf("review" to "2", "caseKey" to "$first:2"),
            mapOf("review" to 2.5, "caseKey" to "$first:2.5"),
            mapOf("review" to true),
            mapOf("review" to 99_999_999_999L, "caseKey" to "$first:99999999999"),
        )
        refused.forEach { overrides ->
            assertThatThrownBy { runBlocking { consumer.consume(held(overrides)) } }
                .describedAs(overrides.toString())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        coVerify(exactly = 0) { cases.createCase(any()) }
        assertThat(outcome("rejected")).isEqualTo(refused.size.toDouble())
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
