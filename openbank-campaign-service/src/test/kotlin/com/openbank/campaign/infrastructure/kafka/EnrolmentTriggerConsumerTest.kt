// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.usecase.TriggeredEnrolment
import com.openbank.campaign.application.usecase.TriggeredEnrolmentService
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.libs.messaging.EventRetry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The ack is the assertion (#5745, #5698).
 *
 * This consumer used to ack in a `finally`, so every outcome — enrolled, dropped, or "the campaigns
 * DB was unreachable" — told Kafka the same thing. With `reset: latest` there is no replay, so a
 * party who qualified for a campaign and hit a blip was never enrolled and never would be, with
 * nothing in lag, the DLQ or any dashboard to say so.
 *
 * Each test below asks the one question the old code could not answer: was this record ACKED or
 * NACKED. A test that only asserted "the consumer did not throw" passes against the broken version.
 */
class EnrolmentTriggerConsumerTest {

    private val partyId = UUID.randomUUID()
    private val campaignId = UUID.randomUUID()

    private val campaigns = mockk<CampaignRepository>()
    private val triggered = mockk<TriggeredEnrolmentService>()

    private fun consumer() = EnrolmentTriggerConsumer(campaigns, triggered, ObjectMapper())

    /** Records how the connector was told to settle the record — the whole point of the fix. */
    private class Settlement {
        var acked = false
        var nacked: Throwable? = null

        private fun done(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

        fun message(payload: String): Message<String> = Message.of(
            payload,
            {
                acked = true
                done()
            },
            { t ->
                nacked = t
                done()
            },
        )
    }

    private fun accountCreated() =
        """{"partyId":"$partyId","eventType":"AccountCreated","occurredAt":"${Instant.EPOCH}"}"""

    private fun campaign() = Campaign(
        id = campaignId,
        name = "account activation",
        goal = "open an account",
        productKind = CampaignProductKind.NONE,
        segmentRef = SegmentRef("eligible", 1),
        steps = listOf(CampaignStep(0, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)),
        conversionRule = "ACCOUNT_OPENED",
        trigger = "ACCOUNT_OPENED",
        state = CampaignState.ACTIVE,
        createdBy = "maker",
        approvedBy = "checker",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /**
     * A persistent downstream failure — the campaigns DB, or Temporal refusing a `startJourney` —
     * must be NACKED. `TriggeredEnrolmentService.enrol` writes a row and starts a workflow, so an
     * acked failure is a party who silently never entered the campaign.
     */
    @Test
    fun `a persistent enrolment failure is nacked after bounded attempts`(): Unit = runBlocking {
        coEvery { campaigns.findActiveByTrigger("ACCOUNT_OPENED") } returns listOf(campaign())
        var attempts = 0
        coEvery { triggered.enrol(campaignId, partyId) } answers {
            attempts++
            throw DownstreamDown("temporal unavailable")
        }
        val settlement = Settlement()

        consumer().onAccountEvent(settlement.message(accountCreated()))

        assertThat(attempts).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
        assertThat(settlement.nacked).isInstanceOf(DownstreamDown::class.java)
        assertThat(settlement.acked).isFalse()
    }

    /**
     * The other half. Without this, rethrowing on the first failure would also pass the test above
     * while turning every transient hiccup into a stopped channel — the outcome the original
     * swallow was written to avoid.
     */
    @Test
    fun `a transient enrolment failure that recovers is retried to success and acked`(): Unit = runBlocking {
        coEvery { campaigns.findActiveByTrigger("ACCOUNT_OPENED") } returns listOf(campaign())
        var attempts = 0
        coEvery { triggered.enrol(campaignId, partyId) } answers {
            attempts++
            if (attempts < EventRetry.DEFAULT_MAX_ATTEMPTS) throw DownstreamDown("connection reset")
            TriggeredEnrolment.ENROLLED
        }
        val settlement = Settlement()

        consumer().onAccountEvent(settlement.message(accountCreated()))

        assertThat(attempts).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
        assertThat(settlement.acked).isTrue()
        assertThat(settlement.nacked).isNull()
    }

    /**
     * The poison pill stays acked, and nothing downstream is asked. This must hold on BOTH sides of
     * the fix — replaying a payload that cannot parse fails identically forever, so a nack would
     * wedge the partition on a record no dead-letter replay could ever drain.
     */
    @Test
    fun `a malformed payload is still acked and never reaches the enrolment service`(): Unit = runBlocking {
        val settlement = Settlement()

        consumer().onAccountEvent(settlement.message("{not json at all"))

        assertThat(settlement.acked).isTrue()
        assertThat(settlement.nacked).isNull()
    }

    /**
     * The overwhelmingly normal case on a shared topic: a well-formed event matching no trigger.
     * It is acked without a repository call — this is the record type that arrives for every party
     * in the bank, and turning it into a lookup would be the expensive kind of correct.
     */
    @Test
    fun `an event matching no trigger is acked without asking the campaign repository`(): Unit = runBlocking {
        val settlement = Settlement()

        consumer().onAccountEvent(
            settlement.message("""{"partyId":"$partyId","eventType":"SomethingElse"}"""),
        )

        assertThat(settlement.acked).isTrue()
        assertThat(settlement.nacked).isNull()
    }

    /**
     * One campaign failing must not cost the OTHERS their enrolment of the same party — the intent
     * of the original per-campaign catch, kept. What changes is the ending: the failure is still
     * reported, so the record comes back and `ALREADY_ENROLLED` makes the succeeded one a no-op.
     */
    @Test
    fun `a failing campaign does not stop the others, and is still nacked at the end`(): Unit = runBlocking {
        val otherId = UUID.randomUUID()
        coEvery { campaigns.findActiveByTrigger("ACCOUNT_OPENED") } returns
            listOf(campaign(), campaign().copy(id = otherId))
        coEvery { triggered.enrol(campaignId, partyId) } throws DownstreamDown("campaign one is broken")
        var otherEnrolled = false
        coEvery { triggered.enrol(otherId, partyId) } answers {
            otherEnrolled = true
            TriggeredEnrolment.ENROLLED
        }
        val settlement = Settlement()

        consumer().onAccountEvent(settlement.message(accountCreated()))

        assertThat(otherEnrolled).isTrue()
        assertThat(settlement.nacked).isInstanceOf(DownstreamDown::class.java)
        assertThat(settlement.acked).isFalse()
    }
}

/** A downstream dependency failing — the transient case, named so the tests read as intent. */
private class DownstreamDown(message: String) : RuntimeException(message)
