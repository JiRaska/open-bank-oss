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
import com.openbank.sanctions.domain.model.SanctionsList
import com.openbank.sanctions.domain.model.SanctionsListType
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

    private fun service(): SanctionsListService {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        val metrics = DomainMetrics().apply { registryInstance = instance }
        return SanctionsListService(repo, importer, clock, publisher, null, metrics)
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

    private fun tickWith(result: ListImportResult) = runBlocking {
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
}
