// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.treasury.application.port.`in`.IntentReconciliation
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.OpenIntentSummary
import com.openbank.treasury.application.port.out.PostingIntentPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** #13883 review: a stuck intent is visible on the gauges, and unreadable evidence is -1, never 0. */
class PostingIntentReconcileSchedulerTest {
    private val now = Instant.parse("2026-10-29T09:00:00Z")
    private val deals = mockk<TreasuryDealUseCase>()
    private val intents = mockk<PostingIntentPort>()
    private val registry = SimpleMeterRegistry()

    private fun scheduler(enabled: Boolean = true) = PostingIntentReconcileScheduler(
        deals,
        intents,
        mockk<DomainMetrics>(relaxed = true),
        registry,
        Clock.fixed(now, ZoneOffset.UTC),
        enabled,
        Duration.ofMinutes(2),
    ).also { it.registerLiveness(mockk(relaxed = true)) }

    private fun gauge(name: String) = registry.get(name).gauge().value()

    @Test
    fun `the gauges report the open count and the oldest intent's age`(): Unit = runBlocking {
        coEvery { deals.reconcilePostingIntents(any()) } returns IntentReconciliation(0, emptyList(), escalated = 1)
        coEvery { intents.summary() } returns OpenIntentSummary(2, now.minus(Duration.ofMinutes(45)))
        scheduler().run()
        assertThat(gauge("openbank.treasury.posting_intents.open")).isEqualTo(2.0)
        assertThat(gauge("openbank.treasury.posting_intents.oldest_age_seconds")).isEqualTo(2700.0)
        assertThat(registry.get("openbank.treasury.posting_intents").tag("outcome", "escalated").counter().count())
            .isEqualTo(1.0)
    }

    @Test
    fun `the applied-after-close counter is exposed at 0 from boot, before any late journal`() {
        scheduler(enabled = false)
        assertThat(registry.get("openbank.treasury.posting_intents.applied_after_close").counter().count()).isZero()
    }

    @Test
    fun `unreadable evidence is -1, and a switched-off reconciler still observes`(): Unit = runBlocking {
        coEvery { intents.summary() } throws IllegalStateException("database down")
        scheduler(enabled = false).run()
        assertThat(gauge("openbank.treasury.posting_intents.open")).isEqualTo(-1.0)
        assertThat(gauge("openbank.treasury.posting_intents.oldest_age_seconds")).isEqualTo(-1.0)
    }
}
