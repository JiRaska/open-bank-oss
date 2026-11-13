// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.KybMonitoringMetricsPort
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryException
import com.openbank.kyb.application.port.out.MonitoredSubjectRegistryPort
import com.openbank.kyb.application.port.out.MonitoringDesired
import com.openbank.kyb.application.port.out.MonitoringRegistration
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
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
 * a failure costs. That the rows exist at all, and commit with the case, is not provable here —
 * `KybOngoingMonitoringIT` holds that against a real database.
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
                    ref = ref,
                ),
            ),
            UboSource.REGISTER,
        ),
        createdAt = now,
        updatedAt = now,
    )

    private fun row(desired: MonitoringDesired, attempts: Int = 0) =
        MonitoringRegistration(7, caseId, ref, desired, generation = 3, attemptCount = attempts)

    @BeforeEach
    fun defaults() {
        coEvery { store.casesToBackfill(any()) } returns emptyList()
        coEvery { store.requeueConfirmedBefore(any(), any(), any()) } returns 0
        coEvery { store.due(any(), any()) } returns emptyList()
        coEvery { store.markSynced(any(), any()) } returns true
        coEvery { store.wantedElsewhere(any(), any()) } returns false
        coEvery { cases.findById(caseId) } returns case()
    }

    @Test
    fun `switched off, a run touches neither the store nor the registry`(): Unit = runBlocking {
        assertThat(service(enabled = false).run()).isEqualTo(MonitoringRunOutcome.DISABLED)

        coVerify(exactly = 0) { store.due(any(), any()) }
        coVerify(exactly = 0) { registry.register(any(), any(), any()) }
        coVerify(exactly = 0) { registry.withdraw(any()) }
    }

    @Test
    fun `a due registration is sent with the name on the case and recorded against its generation`(): Unit =
        runBlocking {
            val due = row(MonitoringDesired.REGISTERED)
            coEvery { store.due(now, 50) } returns listOf(due)

            assertThat(service().run()).isEqualTo(MonitoringRunOutcome.COMPLETED)

            coVerify(exactly = 1) { registry.register(ref, false, "Quentin Example") }
            coVerify(exactly = 1) { store.markSynced(due, now) }
            verify(exactly = 1) { metrics.registrationAttempted("registered") }
            coVerify(exactly = 0) { store.markFailed(any(), any(), any(), any()) }
        }

    @Test
    fun `a delivery the store no longer accepts is not counted as registered`(): Unit = runBlocking {
        coEvery { store.due(now, 50) } returns listOf(row(MonitoringDesired.REGISTERED))
        // The case moved on while the request was in flight: the row is at a newer generation.
        coEvery { store.markSynced(any(), any()) } returns false

        service().run()

        verify(exactly = 0) { metrics.registrationAttempted("registered") }
    }

    @Test
    fun `a refusal is recorded with its reason and retried later, never dropped`(): Unit = runBlocking {
        val due = row(MonitoringDesired.REGISTERED, attempts = 2)
        coEvery { store.due(now, 50) } returns listOf(due)
        coEvery { registry.register(any(), any(), any()) } throws MonitoredSubjectRegistryException("http_503", null)

        assertThat(service().run()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        val next = slot<Instant>()
        coVerify(exactly = 1) { store.markFailed(due, now, capture(next), "http_503") }
        assertThat(Duration.between(now, next.captured)).isEqualTo(Duration.ofSeconds(120))
        verify(exactly = 1) { metrics.registrationAttempted("failed", "http_503") }
        coVerify(exactly = 0) { store.markSynced(any(), any()) }
    }

    @Test
    fun `a withdrawal deletes the subject, unless another case still needs the reference`(): Unit = runBlocking {
        val due = row(MonitoringDesired.WITHDRAWN)
        coEvery { store.due(now, 50) } returns listOf(due)
        coEvery { cases.findById(caseId) } returns case(CaseStatus.REJECTED)

        service().run()
        coVerify(exactly = 1) { registry.withdraw(ref) }
        verify(exactly = 1) { metrics.registrationAttempted("withdrawn") }

        coEvery { store.wantedElsewhere(ref, caseId) } returns true
        service().run()
        coVerify(exactly = 1) { registry.withdraw(ref) }
        coVerify(exactly = 2) { store.markSynced(due, now) }
    }

    @Test
    fun `a row whose subject the case does not carry deletes nothing and re-derives the case`(): Unit = runBlocking {
        val due = row(MonitoringDesired.REGISTERED)
        coEvery { store.due(now, 50) } returns listOf(due)
        val ended = case(CaseStatus.REJECTED)
        coEvery { cases.findById(caseId) } returns ended
        coEvery { cases.update(ended, null) } returns ended

        service().run()

        coVerify(exactly = 0) { registry.register(any(), any(), any()) }
        coVerify(exactly = 0) { registry.withdraw(any()) }
        coVerify(exactly = 1) { cases.update(ended, null) }
        coVerify(exactly = 1) { store.markFailed(due, now, any(), "desired_state_drift") }
    }

    @Test
    fun `an approved case with no rows is written again, which is what creates them`(): Unit = runBlocking {
        val legacy = case().let { c ->
            c.copy(screening = c.screening?.copy(subjects = c.screening!!.subjects.map { it.copy(ref = null) }))
        }
        coEvery { store.casesToBackfill(50) } returns listOf(caseId)
        coEvery { cases.findById(caseId) } returns legacy
        val written = slot<BusinessOnboardingCase>()
        coEvery { cases.update(capture(written), null) } answers { written.captured }

        service().run()

        val ref = written.captured.screening!!.subjects.single().ref
        assertThat(ref).isNotNull()
        assertThat(UUID.fromString(ref).version()).describedAs("a random id, not a time-ordered one").isEqualTo(4)
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
