// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.`in`.MonitoredSubjectRequest
import com.openbank.sanctions.application.port.out.ListedEntry
import com.openbank.sanctions.application.port.out.MonitoredSubjectCandidate
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.application.port.out.MonitoringWorkRepository
import com.openbank.sanctions.application.port.out.PendingEntryChange
import com.openbank.sanctions.application.port.out.SanctionsMonitoringMetricsPort
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MatchKind
import com.openbank.sanctions.domain.model.MatchType
import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.MonitoringFinding
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.SanctionsMatch
import com.openbank.sanctions.domain.model.SanctionsMonitoringDisabled
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The decisions of the monitoring use case that need no database. All names are invented. */
class SanctionsMonitoringServiceTest {

    private val subjects = mockk<MonitoredSubjectRepository>(relaxed = true)
    private val work = mockk<MonitoringWorkRepository>(relaxed = true)
    private val screening = mockk<MonitoringScreening>()
    private val importer = mockk<SanctionsImportService>()
    private val profiles = ScreeningProfileEvaluator(NoJurisdictions)
    private val metrics = mockk<SanctionsMonitoringMetricsPort>(relaxed = true)
    private val now = Instant.parse("2026-11-12T10:00:00Z")

    private fun service(enabled: Boolean = true, maxCandidates: Int = 2) = SanctionsMonitoringService(
        subjects,
        work,
        screening,
        importer,
        profiles,
        SanctionsMonitoringSettings(
            enabled = enabled,
            deltaMaxEntriesPerRun = 20,
            deltaMaxPending = 100,
            deltaMaxCandidatesPerEntry = maxCandidates,
            sweepBatchSize = 25,
            sweepInterval = Duration.ofDays(30),
            claimLease = Duration.ofMinutes(15),
        ),
        metrics,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun subject(swept: Instant? = null) = MonitoredSubject(
        id = UUID.randomUUID(), ownerService = "openbank-kyc", subjectRef = "party-1",
        entityType = EntityType.INDIVIDUAL, name = "Zorvath Quellinbrook", aliases = emptyList(),
        dateOfBirth = null, nationality = null, country = null,
        registeredAt = now, updatedAt = now, lastSweptAt = swept,
    )

    private fun match(score: Double, kind: MatchKind = MatchKind.SANCTIONS) = SanctionsMatch(
        listType = SanctionsListType.OFAC_SDN,
        matchType = MatchType.EXACT,
        matchScore = score,
        matchedName = "Listed Person",
        matchedId = "ext-1",
        listEntryDate = null,
        programs = emptyList(),
        matchKind = kind,
    )

    private fun entry() = ListedEntry(
        SanctionsListType.OFAC_SDN,
        "ext-1",
        "Listed Person",
        emptyList(),
        "listed person",
        true,
        "v-names",
    )

    private fun change(active: Boolean = true) = PendingEntryChange(1, UUID.randomUUID(), "OFAC_SDN", "ext-1", active)

    @Test
    fun `a disabled run reports DISABLED and touches nothing`(): Unit = runBlocking {
        val disabled = service(enabled = false)

        assertThat(disabled.runDelta()).isEqualTo(MonitoringRunOutcome.DISABLED)
        assertThat(disabled.runSweep()).isEqualTo(MonitoringRunOutcome.DISABLED)

        coVerify(exactly = 0) { work.claimPending(any(), any()) }
        coVerify(exactly = 0) { subjects.claimDueForSweep(any(), any(), any()) }
    }

    @Test
    fun `registration is refused while disabled`() {
        assertThatThrownBy {
            runBlocking {
                service(enabled = false)
                    .register("openbank-kyc", "party-1", MonitoredSubjectRequest(EntityType.INDIVIDUAL, "Any Name"))
            }
        }.isInstanceOf(SanctionsMonitoringDisabled::class.java)
        coVerify(exactly = 0) { subjects.upsert(any(), any()) }
    }

    @Test
    fun `deregistration works while disabled`(): Unit = runBlocking {
        coEvery { subjects.delete("openbank-kyc", "party-1") } returns true

        assertThat(service(enabled = false).deregister("openbank-kyc", "party-1")).isTrue()
    }

    @Test
    fun `an entry matching more subjects than the bound requests a re-sweep and stays queued`(): Unit = runBlocking {
        coEvery { work.pendingCount() } returns 1
        coEvery { work.claimPending(any(), any()) } returns listOf(change())
        coEvery { work.findEntry(any()) } returns entry()
        coEvery { subjects.findMatching(any(), any(), 3) } returns List(3) { MonitoredSubjectCandidate(subject(), 1.0) }

        assertThat(service(maxCandidates = 2).runDelta()).isEqualTo(MonitoringRunOutcome.RESWEEP_REQUESTED)

        coVerify(exactly = 1) { work.requestResweep() }
        coVerify(exactly = 0) { work.raise(any()) }
        coVerify(exactly = 0) { work.completePending(any()) }
    }

    @Test
    fun `a delta match covered by a clearance raises nothing, an uncovered one raises a finding`(): Unit = runBlocking {
        val cleared = subject()
        val open = subject()
        coEvery { work.pendingCount() } returns 1
        coEvery { work.claimPending(any(), any()) } returns listOf(change())
        coEvery { work.findEntry(any()) } returns entry()
        coEvery { subjects.findMatching("listed person", 0.65, 3) } returns
            listOf(MonitoredSubjectCandidate(cleared, 0.9), MonitoredSubjectCandidate(open, 0.9))
        every { screening.customerMatch(any(), any(), any(), any(), any()) } returns match(0.9)
        coEvery { screening.isCleared(match { it.idempotencyKey == "monitoring:${cleared.id}" }, any()) } returns true
        coEvery { screening.isCleared(match { it.idempotencyKey == "monitoring:${open.id}" }, any()) } returns false
        val raised = slot<MonitoringFinding>()
        coEvery { work.raise(capture(raised)) } returns true

        assertThat(service().runDelta()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        coVerify(exactly = 1) { work.raise(any()) }
        assertThat(raised.captured.subjectId).isEqualTo(open.id)
        assertThat(raised.captured.listEntryRef).isEqualTo("id:ext-1")
        assertThat(raised.captured.listEntryVersion).isEqualTo("v-names")
        assertThat(raised.captured.detectedAt).isEqualTo(now)
        coVerify(exactly = 1) { work.completePending(1) }
        coVerify(exactly = 1) { metrics.findingRaised(raised.captured) }
    }

    @Test
    fun `a PEP delta match is raised without consulting clearances`(): Unit = runBlocking {
        coEvery { work.pendingCount() } returns 1
        coEvery { work.claimPending(any(), any()) } returns listOf(change())
        coEvery { work.findEntry(any()) } returns entry()
        coEvery { subjects.findMatching(any(), any(), any()) } returns listOf(MonitoredSubjectCandidate(subject(), 0.9))
        every { screening.customerMatch(any(), any(), any(), any(), any()) } returns match(0.9, MatchKind.PEP)
        coEvery { work.raise(any()) } returns true

        service().runDelta()

        coVerify(exactly = 0) { screening.isCleared(any(), any()) }
        coVerify(exactly = 1) { work.raise(match { it.matchKind == MatchKind.PEP }) }
    }

    @Test
    fun `a finding that already exists is not counted as raised`(): Unit = runBlocking {
        coEvery { subjects.claimDueForSweep(any(), any(), any()) } returns listOf(subject())
        coEvery { screening.uncoveredMatches(any()) } returns listOf(match(0.9))
        coEvery { work.namesVersion(SanctionsListType.OFAC_SDN, "id:ext-1") } returns "v-names"
        coEvery { work.raise(any()) } returns false

        assertThat(service().runSweep()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        coVerify(exactly = 0) { metrics.findingRaised(any()) }
    }

    @Test
    fun `a deactivated entry closes its findings and matches nobody`(): Unit = runBlocking {
        coEvery { work.pendingCount() } returns 1
        coEvery { work.claimPending(any(), any()) } returns listOf(change(active = false))
        coEvery { work.findEntry(any()) } returns null

        assertThat(service().runDelta()).isEqualTo(MonitoringRunOutcome.COMPLETED)

        coVerify(exactly = 1) { work.markDelisted("OFAC_SDN", "id:ext-1") }
        coVerify(exactly = 0) { subjects.findMatching(any(), any(), any()) }
    }

    @Test
    fun `a subject that fails to evaluate fails the run, is not marked swept and does not stop the batch`(): Unit =
        runBlocking {
            val broken = subject()
            val healthy = subject(swept = now.minusSeconds(60))
            coEvery { subjects.claimDueForSweep(any(), any(), any()) } returns listOf(broken, healthy)
            coEvery { screening.uncoveredMatches(match { it.idempotencyKey == "monitoring:${broken.id}" }) } throws
                IllegalStateException("synthetic failure")
            coEvery { screening.uncoveredMatches(match { it.idempotencyKey == "monitoring:${healthy.id}" }) } returns
                emptyList()

            assertThat(service().runSweep()).isEqualTo(MonitoringRunOutcome.FAILED)

            coVerify(exactly = 0) { subjects.markSwept(broken.id) }
            coVerify(exactly = 1) { subjects.markSwept(healthy.id) }
        }

    @Test
    fun `a match the live screen would report as CLEAR raises nothing`(): Unit = runBlocking {
        coEvery { subjects.claimDueForSweep(any(), any(), any()) } returns listOf(subject())
        coEvery { screening.uncoveredMatches(any()) } returns listOf(match(0.60))
        coEvery { work.namesVersion(any(), any()) } returns "v-names"

        service().runSweep()

        coVerify(exactly = 0) { work.raise(any()) }
    }
}
