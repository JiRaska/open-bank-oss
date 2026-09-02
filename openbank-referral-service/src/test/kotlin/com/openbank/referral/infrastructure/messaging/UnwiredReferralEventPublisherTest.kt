// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.infrastructure.messaging

import com.openbank.referral.domain.LedgerOutcome
import com.openbank.referral.domain.ReferralEvent
import com.openbank.referral.domain.ReferralPublishOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The unwired transport must be a DISTINCT, COUNTED outcome — never silence.
 *
 * Negative control: restoring the previous empty method body (`override suspend fun
 * publish(event: ReferralEvent) {}`) does not compile against this test at all, because the port
 * now returns [ReferralPublishOutcome]; a body that returns `HANDED_TO_TRANSPORT` while sending
 * nothing fails `drops every event type ...` and `emits no success series`.
 */
class UnwiredReferralEventPublisherTest {

    private val registry = SimpleMeterRegistry()

    private fun publisher(): UnwiredReferralEventPublisher {
        val instance = mockk<Instance<io.micrometer.core.instrument.MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return UnwiredReferralEventPublisher().also { it.registryInstance = instance }
    }

    private val programId: UUID = UUID.randomUUID()
    private val inviteId: UUID = UUID.randomUUID()

    private fun events(): List<ReferralEvent> = listOf(
        ReferralEvent.Qualified(
            UUID.randomUUID(),
            Instant.now(),
            programId,
            inviteId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "evt-1",
        ),
        ReferralEvent.RewardRequested(
            UUID.randomUUID(),
            Instant.now(),
            programId,
            inviteId,
            "referral-1",
            BigDecimal("500.00"),
            "CZK",
        ),
        ReferralEvent.RewardOutcome(
            UUID.randomUUID(),
            Instant.now(),
            programId,
            inviteId,
            "referral-1",
            LedgerOutcome.ACCEPTED,
        ),
    )

    @Test
    fun `drops every event type with an outcome that is not a hand-off`(): Unit = runBlocking {
        val publisher = publisher()
        events().forEach { event ->
            val outcome = publisher.publish(event)
            assertThat(outcome).isEqualTo(ReferralPublishOutcome.TRANSPORT_NOT_WIRED)
            assertThat(outcome).isNotEqualTo(ReferralPublishOutcome.HANDED_TO_TRANSPORT)
            assertThat(outcome.isHandedOff).isFalse()
        }
    }

    @Test
    fun `counts every dropped event, tagged by type and reason`(): Unit = runBlocking {
        val publisher = publisher()
        events().forEach { publisher.publish(it) }

        val dropped = registry.find(UnwiredReferralEventPublisher.DROPPED_COUNTER).counters()
        assertThat(dropped).hasSize(3)
        assertThat(dropped.sumOf { it.count() }).isEqualTo(3.0)
        assertThat(dropped.map { it.id.getTag("event_type") })
            .containsExactlyInAnyOrder("Qualified", "RewardRequested", "RewardOutcome")
        assertThat(dropped.map { it.id.getTag("reason") }.distinct())
            .containsExactly(UnwiredReferralEventPublisher.REASON)
    }

    @Test
    fun `emits no success series that could be mistaken for delivery`(): Unit = runBlocking {
        val publisher = publisher()
        events().forEach { publisher.publish(it) }

        val names = registry.meters.map { it.id.name }
        assertThat(names).containsOnly(UnwiredReferralEventPublisher.DROPPED_COUNTER)
        assertThat(names.none { it.contains("published") || it.contains("sent") || it.contains("delivered") })
            .isTrue()
    }

    @Test
    fun `declares the asyncapi channels it is failing to serve`() {
        assertThat(UnwiredReferralEventPublisher.DECLARED_CHANNELS)
            .containsExactly("referral.qualified.v1", "referral.reward.requested.v1")
    }
}
