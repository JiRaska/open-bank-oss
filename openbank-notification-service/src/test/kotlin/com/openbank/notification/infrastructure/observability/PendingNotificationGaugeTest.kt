// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

class PendingNotificationGaugeTest {
    private val repository = mockk<NotificationRepository>()
    private val registry = SimpleMeterRegistry()

    private fun domainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun successRecorded(): Double? = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "notification-pending-observation")
        .gauge()?.value()

    private fun staleCount(): Double? = registry.find("openbank.notification.pending.stale")
        .tag("service", "notification")
        .gauge()?.value()

    @Test
    fun `successful zero count records liveness`(): Unit = runBlocking {
        every { repository.countStalePending(any()) } returns Uni.createFrom().item(0L)
        val gauge = PendingNotificationGauge(repository, registry, Clock.systemUTC(), domainMetrics())
        gauge.register()

        assertThat(successRecorded()).isEqualTo(0.0)
        gauge.refresh()

        assertThat(staleCount()).isEqualTo(0.0)
        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `failed count is unknown and does not record liveness`(): Unit = runBlocking {
        every { repository.countStalePending(any()) } returns
            Uni.createFrom().failure(IllegalStateException("database unavailable"))
        val gauge = PendingNotificationGauge(repository, registry, Clock.systemUTC(), domainMetrics())
        gauge.register()

        gauge.refresh()

        assertThat(staleCount()).isEqualTo(-1.0)
        assertThat(successRecorded()).isEqualTo(0.0)
    }
}
