// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.scheduler

import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.application.usecase.CasePartyResolver
import com.openbank.aml.infrastructure.client.AccountServiceClient
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

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

    private fun successRecordedOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
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

        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofMinutes(30).toSeconds().toDouble())

        scheduler.sweep()

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `failed sweep records no success`(): Unit = runBlocking {
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

        assertThat(successRecordedOf(registry))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `a lookup that does not resolve stamps the attempt, and one that does sets the party`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val repository = mockk<AmlCaseRepository>(relaxUnitFun = true)
        val accounts = mockk<AccountServiceClient>()
        val resolver = mockk<CasePartyResolver>()
        coEvery { resolver.resolve(any(), any()) } returns true
        val (downCase, downAccount) = UUID.randomUUID() to UUID.randomUUID()
        val (selfCase, selfAccount) = UUID.randomUUID() to UUID.randomUUID()
        val (okCase, okAccount) = UUID.randomUUID() to UUID.randomUUID()
        val party = UUID.randomUUID()
        coEvery { repository.findUnresolvedParty(any()) } returns
            listOf(downCase to downAccount, selfCase to selfAccount, okCase to okAccount)
        coEvery { repository.countUnresolvedParty() } returns 2L
        coEvery { accounts.findPartyByAccountId(downAccount) } returns null
        coEvery { accounts.findPartyByAccountId(selfAccount) } returns selfAccount
        coEvery { accounts.findPartyByAccountId(okAccount) } returns party
        val before = Instant.now()

        val scheduler = PartyResolutionScheduler().apply {
            caseRepository = repository
            this.resolver = resolver
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
        scheduler.sweep()

        // Without the stamp these two would head every later batch and starve the cases behind them.
        coVerify(exactly = 1) { repository.markPartyResolutionAttempted(downCase, match { !it.isBefore(before) }) }
        coVerify(exactly = 1) { repository.markPartyResolutionAttempted(selfCase, any()) }
        coVerify(exactly = 0) { repository.markPartyResolutionAttempted(okCase, any()) }
        coVerify(exactly = 1) { resolver.resolve(okCase, party) }
        coVerify(exactly = 1) { resolver.resolve(any(), any()) }
    }

    private companion object {
        const val WORKFLOW = "aml-party-resolution"
        const val TOLERANCE_SECONDS = 5.0

        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
    }
}
