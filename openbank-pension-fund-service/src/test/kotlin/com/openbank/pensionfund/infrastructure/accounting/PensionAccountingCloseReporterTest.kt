// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.infrastructure.client.PensionBookClient
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class PensionAccountingCloseReporterTest {
    private val config = mockk<PensionAccountingConfig>()
    private val clients = mockk<PensionBookClients>()
    private val evidence = mockk<FundAccountingEvidence>()
    private val store = mockk<PensionFundStore>()
    private val receipts = mockk<FeeDeliveryReceipts>()
    private val fundClient = mockk<PensionBookClient>()
    private val companyClient = mockk<PensionBookClient>()
    private val fundId = UUID.fromString("00000000-0000-0000-0000-000000000101")
    private val companyId = UUID.fromString("00000000-0000-0000-0000-000000000201")
    private val fundUrl = URI("https://fund.example.test")
    private val companyUrl = URI("https://company.example.test")
    private val oldDay = LocalDate.now(ZoneOffset.UTC).minusDays(40)
    private val reportDates = mutableListOf<LocalDate>()
    private val reports = mutableListOf<PensionCloseReport>()
    private val registry = SimpleMeterRegistry()
    private val domainMetrics = DomainMetrics().apply {
        registryInstance = mockk<Instance<MeterRegistry>>().also {
            every { it.isResolvable } returns true
            every { it.get() } returns registry
        }
    }
    private val reporter = PensionAccountingCloseReporter(
        config,
        clients,
        evidence,
        store,
        receipts,
        Clock.systemUTC(),
        domainMetrics,
    )

    init {
        val fund = mockk<Fund>()
        every { fund.id } returns fundId
        coEvery { store.funds() } returns listOf(fund)
        every { config.deliveryEnabled() } returns true
        every { config.closePollInterval() } returns Duration.ofMinutes(5)
        every { config.companyBookId() } returns Optional.of(companyId)
        every { config.companyUrl() } returns Optional.of(companyUrl)
        every { config.funds() } returns mapOf(fundId.toString() to fundUrl)
        coEvery { receipts.bind(fundId, companyId) } returns Unit
        every { clients.open(companyUrl) } returns companyClient
        every { clients.open(fundUrl) } returns fundClient
        every { companyClient.close() } returns Unit
        every { fundClient.close() } returns Unit
        coEvery { fundClient.identity() } returns PensionBookDescriptor("PENSION_FUND", fundId)
        coEvery { fundClient.pendingDays() } returns listOf(PensionClosingDay(oldDay, "CUTOFF"))
        coEvery { fundClient.days(any(), any()) } returns emptyList()
        coEvery { evidence.day(fundId, oldDay, "PENSION_FUND") } returns FundAccountingDayEvidence(
            fundId,
            oldDay,
            "PENSION_FUND",
            sourceNavs = 2,
            journalsPosted = 1,
            blockers = listOf("PENDING_NAV_APPROVAL"),
            sourceRunId = "a".repeat(64),
        )
        coEvery { fundClient.report(capture(reportDates), capture(reports)) } returns Unit
    }

    @Test
    fun `company outage preserves an old pending fund report and remains a cron failure`(): Unit = runBlocking {
        reporter.register(StartupEvent())
        val unavailable = IllegalStateException("company unavailable")
        coEvery { companyClient.identity() } throws unavailable

        repeat(2) {
            assertThat(runCatching { reporter.report() }.exceptionOrNull()).isSameAs(unavailable)
        }

        assertThat(reportDates).containsExactly(oldDay, oldDay)
        assertThat(reports).hasSize(2)
        assertThat(reports[0]).isEqualTo(reports[1])
        val report = reports.first()
        assertThat(report.outcome).isEqualTo("REFUSED")
        assertThat(report.reason).isEqualTo("PENDING_NAV_APPROVAL")
        assertThat(report.itemsProcessed).isEqualTo(2)
        assertThat(report.journalsPosted).isZero()
        assertThat(report.exercisedJobs).isEmpty()
        assertThat(report.sourceRunId).matches("[a-f0-9]{64}")
        coVerify(exactly = 2) { receipts.bind(fundId, companyId) }
        coVerify(exactly = 2) { fundClient.pendingDays() }
        coVerify(exactly = 2) { evidence.day(fundId, oldDay, "PENSION_FUND") }
        coVerify(exactly = 2) { evidence.day(any(), any(), any()) }
        verify(exactly = 2) { fundClient.close() }
        verify(exactly = 2) { companyClient.close() }
        assertThat(successRecorded()).isEqualTo(0.0)
    }

    @Test
    fun `closed days in the readiness month still receive new source refusals`(): Unit = runBlocking {
        val unavailable = IllegalStateException("company unavailable")
        val auditDay = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(1).plusDays(3)
        coEvery { companyClient.identity() } throws unavailable
        coEvery { fundClient.pendingDays() } returns emptyList()
        coEvery { fundClient.days(any(), any()) } returns listOf(PensionClosingDay(auditDay, "TIED_OUT"))
        coEvery { evidence.day(fundId, auditDay, "PENSION_FUND") } returns FundAccountingDayEvidence(
            fundId,
            auditDay,
            "PENSION_FUND",
            1,
            1,
            listOf("PENDING_NAV_APPROVAL"),
            "b".repeat(64),
        )

        assertThat(runCatching { reporter.report() }.exceptionOrNull()).isSameAs(unavailable)
        assertThat(reportDates).containsExactly(auditDay)
        assertThat(reports.single().outcome).isEqualTo("REFUSED")
        assertThat(reports.single().journalsPosted).isZero()
        coVerify(exactly = 1) { evidence.day(fundId, auditDay, "PENSION_FUND") }
    }

    @Test
    fun `cancellation stops polling immediately instead of becoming a destination failure`(): Unit = runBlocking {
        reporter.register(StartupEvent())
        val cancelled = CancellationException("scheduler stopped")
        coEvery { companyClient.identity() } throws cancelled

        assertThat(runCatching { reporter.report() }.exceptionOrNull()).isSameAs(cancelled)

        verify(exactly = 0) { clients.open(fundUrl) }
        coVerify(exactly = 0) { evidence.day(any(), any(), any()) }
        assertThat(reports).isEmpty()
        verify(exactly = 1) { companyClient.close() }
        assertThat(successRecorded()).isEqualTo(0.0)
    }

    @Test
    fun `all destination deliveries record liveness with the configured cadence`(): Unit = runBlocking {
        every { config.closePollInterval() } returns Duration.ofSeconds(17)
        coEvery { companyClient.identity() } returns PensionBookDescriptor("PENSION_COMPANY", companyId)
        coEvery { companyClient.pendingDays() } returns listOf(PensionClosingDay(oldDay, "CUTOFF"))
        coEvery { companyClient.days(any(), any()) } returns emptyList()
        coEvery { evidence.day(fundId, oldDay, "PENSION_COMPANY") } returns FundAccountingDayEvidence(
            fundId,
            oldDay,
            "PENSION_COMPANY",
            2,
            1,
            emptyList(),
            "b".repeat(64),
        )
        coEvery { companyClient.report(any(), any()) } returns Unit
        reporter.register(StartupEvent())
        assertThat(successRecorded()).isEqualTo(0.0)
        assertThat(workflowGauge(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)).isEqualTo(17.0)

        reporter.report()

        assertThat(successRecorded()).isEqualTo(1.0)
        coVerify(exactly = 1) { companyClient.report(oldDay, any()) }
        coVerify(exactly = 1) { fundClient.report(oldDay, any()) }
        assertThat(reports.single().outcome).isEqualTo("REFUSED")
    }

    @Test
    fun `disabled delivery registers no active heartbeat and performs no polling`(): Unit = runBlocking {
        every { config.deliveryEnabled() } returns false

        reporter.register(StartupEvent())
        reporter.report()

        assertThat(successRecorded()).isNull()
        assertThat(workflowGauge(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)).isNull()
        verify(exactly = 0) { config.closePollInterval() }
        verify(exactly = 0) { clients.open(any()) }
        coVerify(exactly = 0) { store.funds() }
    }

    private fun workflowGauge(name: String): Double? = registry.find(name)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, PensionAccountingCloseReporter.WORKFLOW).gauge()?.value()

    private fun successRecorded(): Double? = workflowGauge(WorkflowLivenessMetrics.SUCCESS_RECORDED)
}
