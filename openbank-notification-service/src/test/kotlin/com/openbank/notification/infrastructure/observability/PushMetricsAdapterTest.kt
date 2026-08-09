// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.observability

import com.openbank.notification.domain.model.NotificationOutcome
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.PushSendOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0252 phase 0 — the push counters, and the label hygiene that keeps them queryable. */
class PushMetricsAdapterTest {

    private val registry = SimpleMeterRegistry()
    private val adapter = PushMetricsAdapter(registry)

    @Test
    fun `accepted and skipped land on separate series`() {
        adapter.recordSend(PushPlatform.APNS, PushSendOutcome.ACCEPTED, null)
        adapter.recordSend(PushPlatform.APNS, PushSendOutcome.SKIPPED, "APNs disabled")

        val counters = registry.find("openbank.notification.push.sends").counters()
        assertThat(counters).hasSize(2)
        assertThat(counters.map { it.id.getTag("outcome") }).containsExactlyInAnyOrder("ACCEPTED", "SKIPPED")

        // The point of the split: an environment with the adapter off must not add to ACCEPTED,
        // which is the series the "push channel is dead" alert reads.
        val accepted = registry.find("openbank.notification.push.sends").tag("outcome", "ACCEPTED").counter()
        assertThat(accepted?.count()).isEqualTo(1.0)
    }

    @Test
    fun `a fan-out with no devices is still counted, so zero devices is a number not an absence`() {
        adapter.recordFanOut(NotificationOutcome.FAILED, 0)

        val counter = registry.find("openbank.notification.push.fanouts").counter()
        assertThat(counter?.count()).isEqualTo(1.0)
        assertThat(counter?.id?.getTag("devices_bucket")).isEqualTo("0")
    }

    @Test
    fun `no registry is a no-op rather than a crash`() {
        val inert = PushMetricsAdapter(null)
        inert.recordSend(PushPlatform.FCM, PushSendOutcome.FAILED, "HTTP_410")
        inert.recordFanOut(NotificationOutcome.SENT, 2)
    }

    @Test
    fun `error codes are bounded so a provider body cannot become a label`() {
        assertThat(PushMetricsAdapter.normalizeErrorCode(null)).isEqualTo("none")
        assertThat(PushMetricsAdapter.normalizeErrorCode("  ")).isEqualTo("none")
        assertThat(PushMetricsAdapter.normalizeErrorCode("BadDeviceToken")).isEqualTo("BadDeviceToken")
        // Adapters truncate provider bodies to 200 chars before they reach here; that is still a
        // body, and one distinct label value per rejection would make the series unusable.
        assertThat(PushMetricsAdapter.normalizeErrorCode("x".repeat(200))).isEqualTo("other")
    }

    @Test
    fun `device counts are bucketed, never raw`() {
        assertThat(PushMetricsAdapter.deviceBucket(0)).isEqualTo("0")
        assertThat(PushMetricsAdapter.deviceBucket(1)).isEqualTo("1")
        assertThat(PushMetricsAdapter.deviceBucket(3)).isEqualTo("2-3")
        assertThat(PushMetricsAdapter.deviceBucket(97)).isEqualTo("4+")
    }
}
