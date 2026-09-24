// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.scheduler

import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.application.port.out.PartyDirectoryPort
import com.openbank.aml.application.port.out.PartyPage
import com.openbank.aml.application.port.out.PartySummary
import com.openbank.aml.domain.model.AmlCase
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * Each guard of [OnboardingScreeningReconciler], one test per guard, asserted by what reaches
 * [AmlCaseUseCase.createCase]. The real-cron, real-DB path is `OnboardingScreeningReconcilerIT`.
 */
class OnboardingScreeningReconcilerTest {

    private val parties = mockk<PartyDirectoryPort>()
    private val repository = mockk<AmlCaseRepository>()
    private val useCase = mockk<AmlCaseUseCase>()
    private val registry = SimpleMeterRegistry()
    private lateinit var reconciler: OnboardingScreeningReconciler

    @BeforeEach
    fun setUp() {
        reconciler =
            OnboardingScreeningReconciler(enabled = true, pageSize = 100, maxPages = 20, autoClear = false).also {
                it.parties = parties
                it.caseRepository = repository
                it.amlUseCase = useCase
                val instance = mockk<Instance<MeterRegistry>>()
                every { instance.isResolvable } returns true
                every { instance.get() } returns registry
                it.registryInstance = instance
                it.domainMetrics = mockk(relaxed = true)
                it.register()
            }
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { useCase.createCase(any()) } answers { mockk<AmlCase>(relaxed = true) }
    }

    private fun serve(vararg summaries: PartySummary) {
        coEvery { parties.listPendingKyc(0, any()) } returns PartyPage(summaries.toList(), hasMore = false)
    }

    private fun party(type: String = "SOLE_TRADER", status: String = "PENDING_KYC", kyc: String = "APPROVED") =
        PartySummary(UUID.randomUUID(), type, status, kyc)

    private fun openedCounter() = registry.get("openbank.aml.onboarding.cases_opened_by_reconcile").counter().count()

    @Test
    fun `a stuck party of each screened type gets a case under the consumer's idempotency key`(): Unit = runBlocking {
        val stuck = listOf(party("INDIVIDUAL"), party("SOLE_TRADER"), party("COMPANY"))
        serve(*stuck.toTypedArray())

        assertThat(reconciler.reconcileOnce()).isEqualTo(3)

        stuck.forEach { p ->
            coVerify(exactly = 1) {
                useCase.createCase(
                    match {
                        it.idempotencyKey == "${p.partyId}:CUSTOMER_ONBOARDING" &&
                            it.partyId == p.partyId
                    },
                )
            }
        }
        assertThat(openedCounter()).isEqualTo(3.0)
    }

    @Test
    fun `KYC not approved is skipped`(): Unit = runBlocking {
        serve(party(kyc = "IN_PROGRESS"), party(kyc = "REJECTED"))
        assertThat(reconciler.reconcileOnce()).isZero()
        coVerify(exactly = 0) { useCase.createCase(any()) }
    }

    @Test
    fun `an unscreened party type is skipped`(): Unit = runBlocking {
        serve(party(type = "TRUST"))
        assertThat(reconciler.reconcileOnce()).isZero()
        coVerify(exactly = 0) { useCase.createCase(any()) }
    }

    @Test
    fun `a party no longer PENDING_KYC is skipped`(): Unit = runBlocking {
        serve(party(status = "ACTIVE"))
        assertThat(reconciler.reconcileOnce()).isZero()
        coVerify(exactly = 0) { useCase.createCase(any()) }
    }

    @Test
    fun `a party that already has an onboarding case is left to it`(): Unit = runBlocking {
        val p = party()
        serve(p)
        coEvery { repository.findByIdempotencyKey("${p.partyId}:CUSTOMER_ONBOARDING") } returns mockk(relaxed = true)

        assertThat(reconciler.reconcileOnce()).isZero()
        coVerify(exactly = 0) { useCase.createCase(any()) }
        assertThat(openedCounter()).isZero()
    }

    @Test
    fun `a disabled reconciler asks party-service nothing`(): Unit = runBlocking {
        reconciler.enabled = false
        reconciler.tick()
        coVerify(exactly = 0) { parties.listPendingKyc(any(), any()) }
    }

    @Test
    fun `later pages are walked, and the walk stops at max-pages`(): Unit = runBlocking {
        val onPage1 = party()
        coEvery { parties.listPendingKyc(0, any()) } returns
            PartyPage(listOf(party(kyc = "IN_PROGRESS")), hasMore = true)
        coEvery { parties.listPendingKyc(1, any()) } returns PartyPage(listOf(onPage1), hasMore = true)
        reconciler.maxPages = 2

        assertThat(reconciler.reconcileOnce()).isEqualTo(1)
        coVerify(exactly = 1) { useCase.createCase(match { it.partyId == onPage1.partyId }) }
        coVerify(exactly = 0) { parties.listPendingKyc(2, any()) }
    }

    @Test
    fun `one party's failure does not starve the rest`(): Unit = runBlocking {
        val broken = party()
        val fine = party()
        serve(broken, fine)
        coEvery { useCase.createCase(match { it.partyId == broken.partyId }) } throws IllegalStateException("db")

        assertThat(reconciler.reconcileOnce()).isEqualTo(1)
        coVerify(exactly = 1) { useCase.createCase(match { it.partyId == fine.partyId }) }
        assertThat(registry.get("openbank.aml.onboarding.reconcile_failures").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `party-service unreachable fails the tick instead of reading as nothing stuck`() {
        coEvery { parties.listPendingKyc(any(), any()) } throws IllegalStateException("connection refused")
        assertThrows<IllegalStateException> { runBlocking { reconciler.reconcileOnce() } }
    }
}
