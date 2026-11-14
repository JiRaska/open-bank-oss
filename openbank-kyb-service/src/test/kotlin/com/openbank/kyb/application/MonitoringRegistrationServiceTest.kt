// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.ConcurrentCaseUpdateException
import com.openbank.kyb.application.port.out.KybMonitoringMetricsPort
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryPort
import com.openbank.kyb.application.port.out.MonitoringDesired
import com.openbank.kyb.application.port.out.MonitoringRegistration
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.RegistryFailure
import com.openbank.kyb.application.usecase.MonitoringRegistrationService
import com.openbank.kyb.application.usecase.MonitoringRunOutcome
import com.openbank.kyb.application.usecase.MonitoringSettings
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseScreening
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.ScreeningSubject
import com.openbank.kyb.domain.model.ScreeningSubjectKind
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.kyb.domain.model.UboSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The relay's decisions with every collaborator mocked: what is sent, what is recorded, and what
 * a failure costs. That the rows exist at all, commit with the case, and are delivered by one
 * relay at a time is not provable here — `KybOngoingMonitoringIT` holds that against a real
 * database.
 */
class MonitoringRegistrationServiceTest {

    private val now: Instant = Instant.parse("2026-11-13T10:00:00Z")
    private val caseId: UUID = UUID.fromString("0190a000-0000-7000-8000-0000000000c1")
    private val ref = "5b0c2d3e-0000-4000-8000-0000000000aa"

    private val store = mockk<MonitoringRegistrationStore>(relaxed = true)
    private val cases = mockk<BusinessOnboardingCaseRepository>()
    private val registry = mockk<MonitoredSubjectRegistryPort>(relaxed = true)
    private val metrics = mockk<KybMonitoringMetricsPort>(relaxed = true)

    private fun service(enabled: Boolean = true) = MonitoringRegistrationService().also {
        it.store = store
        it.cases = cases
        it.registry = registry
        it.metrics = metrics
        it.settings = MonitoringSettings(enabled, 50, Duration.ofDays(7), "openbank-kyb")
        it.clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun case(status: CaseStatus = CaseStatus.ACTIVE) = BusinessOnboardingCase(
        id = caseId,
        identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649"),
        initiatorPartyId = UUID.fromString("0190a000-0000-7000-8000-0000000000b1"),
        status = status,
        extract = null,
        entityPartyId = null,
        requiredSignatures = 1,
        signers = emptyList(),
        reviewReason = null,
        screening = CaseScreening(
            listOf(
                ScreeningSubject(
                    key = "k1",
                    kind = ScreeningSubjectKind.REPRESENTATIVE,
                    name = "Quentin Example",
                    organisation = false,
                    sanctions = ScreeningVerdict.CLEAR,
                ),
            ),
            UboSource.REGISTER,
        ),
        createdAt = now,
        updatedAt = now,
    )

    private fun row(desired: MonitoringDesired, attempts: Int = 0, id: Long = 7) =
        MonitoringRegistration(id, caseId, "k1", ref, desired, generation = 3, attemptCount = attempts, claim = now)

    /** The store hands out [rows] one claim at a time, then nothing. */
    private fun due(vararg rows: MonitoringRegistration) {
        coEvery { store.claimNext(any(), any()) } returnsMany rows.toList() + listOf(null)
    }

    @BeforeEach
    fun defaults() {
        coEvery { store.casesToReconcile(any()) } returns emptyList()
        coEvery { store.requeueSettledBefore(any(), any(), any()) } returns 0
        coEvery { store.claimNext(any(), any()) } returns null
        coEvery { store.markSynced(any(), any()) } returns true
        coEvery { cases.findById(caseId) } returns case()
    }

    @Test
    fun `switched off, a run touches neither the store nor the registry`(): Unit = runBlocking {
        assertThat(service(enabled = false).run()).isEqualTo(MonitoringRunOutcome.DISABLED)

        coVerify(exactly = 0) { store.claimNext(any(), any()) }
        coVerify(exactly = 0) { store.casesToReconcile(any()) }
        coVerify(exactly = 0) { registry.register(any(), any(), any()) }
        coVerify(exactly = 0) { registry.withdraw(any()) }
    }

    @Test
    fun `a claimed registration is sent under its reference with the name on the case`(): Unit = runBlocking {
        val claimed = row(MonitoringDesired.REGISTERED)
        due(claimed)

        assertThat(service().run()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        coVerify(exactly = 1) { registry.register(ref, false, "Quentin Example") }
        coVerify(exactly = 1) { store.markSynced(claimed, now) }
        verify(exactly = 1) { metrics.registrationAttempted("registered") }
        // The lease outlasts the call: claimed for a minute.
        coVerify { store.claimNext(now, now.plusSeconds(60)) }
    }

    @Test
    fun `a delivery the store no longer accepts is not counted`(): Unit = runBlocking {
        due(row(MonitoringDesired.REGISTERED))
        // The case moved on while the request was in flight, or another relay holds the row now.
        coEvery { store.markSynced(any(), any()) } returns false

        service().run()

        verify(exactly = 0) { metrics.registrationAttempted("registered") }
    }

    @Test
    fun `an outage is recorded with its reason and retried later, never dropped`(): Unit = runBlocking {
        val claimed = row(MonitoringDesired.REGISTERED, attempts = 2)
        due(claimed)
        coEvery { registry.register(any(), any(), any()) } throws
            MonitoredSubjectRegistryException("http_503", RegistryFailure.TRANSIENT, null)

        assertThat(service().run()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        val next = slot<Instant>()
        coVerify(exactly = 1) { store.markFailed(claimed, now, capture(next), "http_503") }
        assertThat(Duration.between(now, next.captured)).isEqualTo(Duration.ofSeconds(120))
        verify(exactly = 1) { metrics.registrationAttempted("failed", "http_503") }
        coVerify(exactly = 0) { store.markSynced(any(), any()) }
    }

    @Test
    fun `a subject the registry refuses leaves the retry loop, a wrong caller does not`(): Unit = runBlocking {
        val claimed = row(MonitoringDesired.REGISTERED)
        due(claimed)
        coEvery { registry.register(any(), any(), any()) } throws
            MonitoredSubjectRegistryException("http_400", RegistryFailure.REFUSED, null)
        service().run()
        coVerify(exactly = 1) { store.markRefused(claimed, now, "http_400") }
        coVerify(exactly = 0) { store.markFailed(any(), any(), any(), any()) }
        verify(exactly = 1) { metrics.registrationAttempted("refused", "http_400") }

        due(claimed)
        coEvery { registry.register(any(), any(), any()) } throws
            MonitoredSubjectRegistryException("http_403", RegistryFailure.UNAUTHORISED, null)
        service().run()
        // Still owed and still retried: it heals when the caller is fixed, and it is counted apart.
        coVerify(exactly = 1) { store.markFailed(claimed, now, any(), "http_403") }
        coVerify(exactly = 1) { store.markRefused(any(), any(), any()) }
        verify(exactly = 1) { metrics.registrationAttempted("unauthorised", "http_403") }
    }

    @Test
    fun `a withdrawal deletes the subject by its reference`(): Unit = runBlocking {
        val claimed = row(MonitoringDesired.WITHDRAWN)
        due(claimed)

        service().run()

        coVerify(exactly = 1) { registry.withdraw(ref) }
        coVerify(exactly = 1) { store.markSynced(claimed, now) }
        verify(exactly = 1) { metrics.registrationAttempted("withdrawn") }
    }

    @Test
    fun `a row whose subject the case does not carry touches nothing in the registry`(): Unit = runBlocking {
        val claimed = row(MonitoringDesired.REGISTERED)
        due(claimed)
        // The case was last written by something that did not synchronise the store.
        coEvery { cases.findById(caseId) } returns case(CaseStatus.REJECTED)

        service().run()

        coVerify(exactly = 0) { registry.register(any(), any(), any()) }
        coVerify(exactly = 0) { registry.withdraw(any()) }
        coVerify(exactly = 1) { store.markFailed(claimed, now, any(), "desired_state_drift") }
    }

    @Test
    fun `a case whose last writer did not synchronise is written again, unchanged`(): Unit = runBlocking {
        coEvery { store.casesToReconcile(50) } returns listOf(caseId)
        val stored = case()
        val written = slot<BusinessOnboardingCase>()
        coEvery { cases.update(capture(written), null) } answers { written.captured }

        service().run()

        // The write itself is what brings the store to the case; nothing about the case changes.
        assertThat(written.captured).isEqualTo(stored)
    }

    @Test
    fun `one case or row that fails does not cost the others their turn`(): Unit = runBlocking {
        val other = UUID.fromString("0190a000-0000-7000-8000-0000000000c2")
        coEvery { store.casesToReconcile(50) } returns listOf(other, caseId)
        coEvery { cases.findById(other) } throws IllegalStateException("connection lost")
        coEvery { cases.update(any(), null) } throws ConcurrentCaseUpdateException(caseId)
        val first = row(MonitoringDesired.REGISTERED)
        val second = row(MonitoringDesired.REGISTERED, id = 8)
        due(first, second)
        coEvery { store.markSynced(first, now) } throws IllegalStateException("connection lost")

        assertThat(service().run()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        coVerify(exactly = 1) { store.markSynced(second, now) }
        verify(exactly = 2) { metrics.registrationAttempted("failed", "internal") }
    }

    @Test
    fun `the backoff starts at thirty seconds, doubles, and never exceeds an hour`() {
        assertThat(MonitoringRegistrationService.backoff(0)).isEqualTo(Duration.ofSeconds(30))
        assertThat(MonitoringRegistrationService.backoff(1)).isEqualTo(Duration.ofSeconds(60))
        assertThat(MonitoringRegistrationService.backoff(6)).isEqualTo(Duration.ofSeconds(1920))
        assertThat(MonitoringRegistrationService.backoff(7)).isEqualTo(Duration.ofHours(1))
        assertThat(MonitoringRegistrationService.backoff(10_000)).isEqualTo(Duration.ofHours(1))
    }
}
