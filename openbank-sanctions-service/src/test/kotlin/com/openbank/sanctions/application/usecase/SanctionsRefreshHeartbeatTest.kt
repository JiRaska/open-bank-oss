// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.application.usecase

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.sanctions.application.port.out.ListImportOutcome
import com.openbank.sanctions.application.port.out.ListImportResult
import com.openbank.sanctions.application.port.out.SanctionsChangePublisher
import com.openbank.sanctions.application.port.out.SanctionsPublicationOutcome
import com.openbank.sanctions.application.port.out.SanctionsPublicationPermit
import com.openbank.sanctions.domain.model.SanctionsList
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsImportPublicationFence
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsListRepositoryImpl
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The 60s sanctions-list refresh tick had no heartbeat: if it stopped, screening data went stale
 * with nothing to say so. A tick that finishes with a failed list must NOT count as a success,
 * otherwise a feed failing every minute would read as healthy.
 */
class SanctionsRefreshHeartbeatTest {

    private val repo = mockk<SanctionsListRepositoryImpl>()
    private val importer = mockk<SanctionsImportService>()
    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val publisher = mockk<SanctionsChangePublisher>()
    private val registry = SimpleMeterRegistry()

    private fun service(fence: SanctionsImportPublicationFence? = null): SanctionsListService {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        val metrics = DomainMetrics().apply { registryInstance = instance }
        return SanctionsListService(repo, importer, clock, publisher, fence, metrics)
    }

    private fun successRecorded(): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "sanctions-list-refresh")
        .gauge()
        ?.value()

    private fun aList(type: String) = SanctionsList(
        id = java.util.UUID.randomUUID(),
        listType = type,
        displayName = type,
        sourceUrl = "https://example.com/feed",
        enabled = false,
        lastUpdatedAt = null,
        lastEntryCount = null,
        cronHour = 6,
        cronMinute = 0,
        cronDays = "MON",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `the heartbeat is registered at construction and flips after a clean tick`(): Unit = runBlocking {
        coEvery { repo.listSanctionsLists() } returns listOf(aList("OFAC_SDN"))
        coEvery { publisher.publishPending(any(), any()) } returns SanctionsPublicationOutcome.NO_CHANGES
        val service = service()
        assertThat(successRecorded()).isEqualTo(0.0)

        service.scheduledRefresh()

        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `a tick where a list fails is not a success`(): Unit = runBlocking {
        coEvery { repo.listSanctionsLists() } returns listOf(aList("OFAC_SDN"))
        coEvery { publisher.publishPending(any(), any()) } throws IllegalStateException("db hiccup")
        val service = service()

        service.scheduledRefresh()

        assertThat(successRecorded()).isEqualTo(0.0)
    }

    private fun dueList() = aList("OFAC_SDN").copy(enabled = true, refreshRequestedAt = Instant.EPOCH)

    private fun tickWith(result: ListImportResult): Unit = runBlocking {
        val list = dueList()
        coEvery { repo.listSanctionsLists() } returns listOf(list)
        coEvery { repo.findByListType("OFAC_SDN") } returns list
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, any()) } returns result
        coEvery { publisher.publishPending(any(), any()) } returns SanctionsPublicationOutcome.NO_CHANGES
        coEvery { repo.markUpdated("OFAC_SDN", any()) } returns list
        service().scheduledRefresh()
    }

    @Test
    fun `a refresh that imports the feed is a success`() {
        tickWith(ListImportResult(ListImportOutcome.IMPORTED, 5))

        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `a refresh that keeps the existing list after a failed import is not a success`() {
        tickWith(ListImportResult(ListImportOutcome.FAILED_KEPT_EXISTING, 0))

        assertThat(successRecorded()).isEqualTo(0.0)
    }

    @Test
    fun `a refresh that finds an empty feed is not a success`() {
        tickWith(ListImportResult(ListImportOutcome.EMPTY_FEED, 0))

        assertThat(successRecorded()).isEqualTo(0.0)
    }

    @Test
    fun `a list that failed at its cron minute keeps the heartbeat stale on the next tick`(): Unit = runBlocking {
        val due = dueList()
        val idle = due.copy(refreshRequestedAt = null)
        coEvery { repo.listSanctionsLists() } returns listOf(due)
        coEvery { repo.findByListType("OFAC_SDN") } throws IllegalStateException("db hiccup")
        coEvery { publisher.publishPending(any(), any()) } returns SanctionsPublicationOutcome.NO_CHANGES
        val service = service()

        service.scheduledRefresh()
        assertThat(successRecorded()).isEqualTo(0.0)

        // Tick 2: the cron minute has passed, nothing is due, yet the last attempt still failed.
        coEvery { repo.listSanctionsLists() } returns listOf(idle)
        service.scheduledRefresh()

        assertThat(successRecorded()).isEqualTo(0.0)
    }

    @Test
    fun `a later successful refresh of the failed list restores the heartbeat`(): Unit = runBlocking {
        val due = dueList()
        coEvery { repo.listSanctionsLists() } returns listOf(due)
        coEvery { repo.findByListType("OFAC_SDN") } returns due
        coEvery { publisher.publishPending(any(), any()) } returns SanctionsPublicationOutcome.NO_CHANGES
        coEvery { repo.markUpdated("OFAC_SDN", any()) } returns due
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, any()) } returnsMany listOf(
            ListImportResult(ListImportOutcome.FAILED_KEPT_EXISTING, 0),
            ListImportResult(ListImportOutcome.IMPORTED, 5),
        )
        val service = service()

        service.scheduledRefresh()
        assertThat(successRecorded()).isEqualTo(0.0)
        service.scheduledRefresh()

        assertThat(successRecorded()).isEqualTo(1.0)
    }

    private fun fencedTick(result: ListImportResult, inheritedIncomplete: Boolean): Unit = runBlocking {
        val list = dueList()
        val fence = mockk<SanctionsImportPublicationFence>()
        val permit = SanctionsPublicationPermit(SanctionsListType.OFAC_SDN, 1, inheritedIncomplete)
        coEvery { fence.duringRefresh<SanctionsList>(SanctionsListType.OFAC_SDN, any()) } coAnswers {
            secondArg<suspend (SanctionsPublicationPermit) -> SanctionsList>().invoke(permit)
        }
        coEvery { repo.listSanctionsLists() } returns listOf(list)
        coEvery { repo.findByListType("OFAC_SDN") } returns list
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, any(), any()) } returns result
        coEvery { repo.markRetryPendingFenced("OFAC_SDN", any()) } returns list
        service(fence).scheduledRefresh()
    }

    @Test
    fun `the fenced branch reports a failed import as deferred, not a success`() {
        fencedTick(ListImportResult(ListImportOutcome.FAILED_KEPT_EXISTING, 0), inheritedIncomplete = false)

        assertThat(successRecorded()).isEqualTo(0.0)
    }

    @Test
    fun `the fenced branch reports an inherited incomplete import as deferred, not a success`() {
        fencedTick(ListImportResult(ListImportOutcome.SKIPPED_NOT_ENTITY_BASED, 0), inheritedIncomplete = true)

        assertThat(successRecorded()).isEqualTo(0.0)
    }
}
