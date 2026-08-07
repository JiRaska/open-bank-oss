// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.scheduler

import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.infrastructure.client.AccountServiceClient
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class PartyResolutionWorkflowLivenessTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ageOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
        .gauge()
        ?.value()

    @Test
    fun `scheduler registers gauge at startup and records success after a completed sweep`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repository = mockk<AmlCaseRepository>()
        val accounts = mockk<AccountServiceClient>()
        coEvery { repository.findUnresolvedParty(any()) } returns emptyList()
        coEvery { repository.countUnresolvedParty() } returns 0L

        val scheduler = PartyResolutionScheduler().apply {
            caseRepository = repository
            this.accounts = accounts
            registryInstance = mockk<Instance<MeterRegistry>>().also {
                every { it.isResolvable } returns true
                every { it.get() } returns registry
            }
            domainMetrics = metricsOver(registry)
            enabled = true
            batchLimit = 50
        }

        scheduler.register()

        assertThat(ageOf(registry)).isGreaterThan(FIFTY_YEARS_SECONDS)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofMinutes(30).toSeconds().toDouble())

        scheduler.sweep()

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `failed sweep leaves liveness old`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repository = mockk<AmlCaseRepository>()
        val accounts = mockk<AccountServiceClient>()
        coEvery { repository.findUnresolvedParty(any()) } returns emptyList()
        coEvery { repository.countUnresolvedParty() } throws IllegalStateException("db down")

        val scheduler = PartyResolutionScheduler().apply {
            caseRepository = repository
            this.accounts = accounts
            registryInstance = mockk<Instance<MeterRegistry>>().also {
                every { it.isResolvable } returns true
                every { it.get() } returns registry
            }
            domainMetrics = metricsOver(registry)
            enabled = true
            batchLimit = 50
        }
        scheduler.register()

        assertThatThrownBy { runBlocking { scheduler.sweep() } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(ageOf(registry)).isGreaterThan(FIFTY_YEARS_SECONDS)
    }

    private companion object {
        const val WORKFLOW = "aml-party-resolution"
        const val TOLERANCE_SECONDS = 5.0
        val FIFTY_YEARS_SECONDS = Duration.ofDays(50 * 365).toSeconds().toDouble()
    }
}
