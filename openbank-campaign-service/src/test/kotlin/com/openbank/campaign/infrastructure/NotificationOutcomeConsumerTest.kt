// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignOutcomeCount
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.port.out.StepOutcomeCount
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.campaign.infrastructure.kafka.NotificationOutcomeConsumer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-0239 D3, issue #3663 — the parsing and routing half of the consumer.
 *
 * The transition rule it delegates to is covered by `DeliveryTransitionTest`; what is asserted here
 * is everything between a record on a SHARED topic and the repository call: which records reach the
 * send log at all, and that the ones that must not reach it fail quietly rather than wedging the
 * channel.
 */
class NotificationOutcomeConsumerTest {

    private class Applied(val sendId: UUID, val outcome: String, val reason: String?, val occurredAt: Instant)

    private class RecordingSendLog : SendLogRepository {
        val applied = mutableListOf<Applied>()
        var throwOnApply: RuntimeException? = null

        override suspend fun applyDeliveryOutcome(
            sendId: UUID,
            outcome: String,
            reason: String?,
            occurredAt: Instant,
        ): Boolean {
            throwOnApply?.let { throw it }
            applied += Applied(sendId, outcome, reason, occurredAt)
            return true
        }

        override suspend fun record(send: SendRecord) = Unit
        override suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long) = 0
        override suspend fun listByCampaign(campaignId: UUID, outcome: SendOutcome?, page: Int, size: Int) =
            emptyList<SendRecord>()

        override suspend fun countByCampaign(campaignId: UUID, outcome: SendOutcome?) = 0L
        override suspend fun countByStepAndOutcome(campaignId: UUID) = emptyList<StepOutcomeCount>()
        override suspend fun countSendsForPartyInCampaign(campaignId: UUID, partyId: UUID) = 0
        override suspend fun latestDeliveryStatusBeforeStep(
            campaignId: UUID,
            partyId: UUID,
            stepOrder: Int,
        ): DeliveryStatus? = null
        override suspend fun countAllByCampaignAndOutcome() = emptyList<CampaignOutcomeCount>()
    }

    private val sendLog = RecordingSendLog()
    private val consumer = NotificationOutcomeConsumer(sendLog, ObjectMapper())

    private fun outcomeJson(correlationId: String?, outcome: String, reason: String?): String {
        val correlation = correlationId?.let { "\"$it\"" } ?: "null"
        val reasonJson = reason?.let { "\"$it\"" } ?: "null"
        return """
            {
              "notificationId": "${UUID.randomUUID()}",
              "correlationId": $correlation,
              "partyId": "${UUID.randomUUID()}",
              "channel": "EMAIL",
              "template": "MARKETING_PRODUCT_OFFER",
              "outcome": "$outcome",
              "reason": $reasonJson,
              "occurredAt": "2026-08-06T09:15:42.113Z"
            }
        """.trimIndent()
    }

    /**
     * The case the whole issue is about: a marketing send the ADR-0198 D4 consent gate refused. The
     * campaign recorded `SENT` (the handoff really was accepted) and had no way to learn otherwise.
     */
    @Test
    fun `a suppression is routed to the correlated send with its reason intact`(): Unit = runBlocking {
        val sendId = UUID.randomUUID()

        consumer.onOutcome(outcomeJson(sendId.toString(), "SUPPRESSED", "no_active_consent"))

        assertThat(sendLog.applied).hasSize(1)
        val applied = sendLog.applied.single()
        assertThat(applied.sendId).isEqualTo(sendId)
        assertThat(applied.outcome).isEqualTo("SUPPRESSED")
        // The reason is carried, not flattened: "the GDPR control worked" and "consent-service was
        // down and we failed closed" are different facts (ADR-0198 D4) and must stay countable apart.
        assertThat(applied.reason).isEqualTo("no_active_consent")
        // The PRODUCER's timestamp, not the consumer's receipt time.
        assertThat(applied.occurredAt).isEqualTo(Instant.parse("2026-08-06T09:15:42.113Z"))
    }

    /**
     * The topic carries every producer's traffic, and most of it correlates with nothing here. This
     * is the normal case — it must cost a repository call of exactly zero, not a lookup per record.
     */
    @Test
    fun `an outcome with no correlation id never reaches the send log`(): Unit = runBlocking {
        consumer.onOutcome(outcomeJson(null, "SENT", null))
        consumer.onOutcome("""{"notificationId":"${UUID.randomUUID()}","outcome":"SENT"}""")
        consumer.onOutcome(outcomeJson("not-a-uuid", "SENT", null))

        assertThat(sendLog.applied).isEmpty()
    }

    /** A record with no usable outcome is not a reason to ask the repository anything. */
    @Test
    fun `an outcome with a blank status is dropped before the repository`(): Unit = runBlocking {
        consumer.onOutcome(outcomeJson(UUID.randomUUID().toString(), "", null))

        assertThat(sendLog.applied).isEmpty()
    }

    /**
     * A poison record must not wedge the partition. Both halves are asserted: it does not throw, and
     * a well-formed record after it is still processed — a consumer that survives the bad record but
     * stops consuming would pass the first assertion alone.
     */
    @Test
    fun `an unparseable record is dropped and the next one is still processed`(): Unit = runBlocking {
        val sendId = UUID.randomUUID()

        consumer.onOutcome("{not json at all")
        consumer.onOutcome(outcomeJson(sendId.toString(), "SENT", null))

        assertThat(sendLog.applied.map { it.sendId }).containsExactly(sendId)
    }

    /**
     * A repository failure is swallowed for the same reason. The row stays PENDING — which already
     * means "no outcome arrived" — rather than the channel stopping and EVERY row staying pending.
     */
    @Test
    fun `a repository failure leaves the channel draining`(): Unit = runBlocking {
        sendLog.throwOnApply = IllegalStateException("connection reset")
        consumer.onOutcome(outcomeJson(UUID.randomUUID().toString(), "SENT", null))

        sendLog.throwOnApply = null
        val sendId = UUID.randomUUID()
        consumer.onOutcome(outcomeJson(sendId.toString(), "SENT", null))

        assertThat(sendLog.applied.map { it.sendId }).containsExactly(sendId)
    }
}
