// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.tax.application.port.out.ReturnCatalogueSource
import com.openbank.tax.application.port.out.ReturnDataPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.port.out.StatutoryReturnMetricsPort
import com.openbank.tax.application.port.out.StatutoryReturnRepository
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxValidationException
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.domain.returns.ReturnScope
import com.openbank.tax.domain.returns.ReturnStatus
import com.openbank.tax.domain.returns.StatutoryReturn
import com.openbank.tax.domain.returns.ValidationRule
import com.openbank.tax.infrastructure.returns.StatutoryReturnDeadlineScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class StatutoryReturnServiceTest {
    private val monthly = ReturnDefinition(
        code = "M-FUND",
        name = "monthly fund",
        scope = ReturnScope.FUND,
        periodicity = Periodicity.MONTH,
        deadlineDaysAfterPeriodEnd = 20,
        datapoints = listOf("units"),
        rules = listOf(ValidationRule.NonNegative("units-non-negative", listOf("units"))),
        legalBasis = "test",
    )
    private val quarterly = ReturnDefinition(
        code = "Q-CO",
        name = "quarterly company",
        scope = ReturnScope.COMPANY,
        periodicity = Periodicity.QUARTER,
        deadlineDaysAfterPeriodEnd = 30,
        datapoints = listOf("capital"),
        rules = emptyList(),
        legalBasis = "test",
    )
    private val catalogue = ReturnCatalogue("test-cat", 1, "XX", false, listOf(monthly, quarterly))

    // 25 Aug 2026: July has ended and its monthly deadline (20 Aug) has passed; Q2's (30 Jul) too.
    private val clock = Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC)

    private class InMemoryRepo : StatutoryReturnRepository {
        val rows = linkedMapOf<UUID, StatutoryReturn>()
        override suspend fun insert(statutoryReturn: StatutoryReturn) = statutoryReturn.also { rows[it.id] = it }
        override suspend fun findReturn(id: UUID) = rows[id]
        override suspend fun latestRevision(
            catalogueId: String,
            returnCode: String,
            entityId: String,
            period: ReportingPeriod,
        ) = rows.values.filter {
            it.catalogueId == catalogueId &&
                it.returnCode == returnCode &&
                it.entityId == entityId &&
                it.period == period
        }
            .maxByOrNull { it.revision }
        override suspend fun listReturns() = rows.values.toList()
        override suspend fun save(statutoryReturn: StatutoryReturn, expectedVersion: Long): StatutoryReturn {
            check(rows.getValue(statutoryReturn.id).version == expectedVersion) { "stale" }
            rows[statutoryReturn.id] = statutoryReturn
            return statutoryReturn
        }
    }

    private class FixedData(var values: Map<String, BigDecimal>?) : ReturnDataPort {
        override val available = values != null
        override suspend fun fetch(
            catalogue: ReturnCatalogue,
            definition: ReturnDefinition,
            entityId: String,
            period: ReportingPeriod,
        ) = values ?: throw ReturnDataUnavailableException("unbound")
    }

    private val repo = InMemoryRepo()
    private val data = FixedData(mapOf("units" to BigDecimal("100")))

    private fun service(
        start: LocalDate? = LocalDate.of(2026, 8, 1),
        d: ReturnDataPort = data,
        fundIds: List<String> = listOf("fund-a", "fund-b"),
    ) = StatutoryReturnService(
        catalogues = object : ReturnCatalogueSource {
            override fun catalogues() = listOf(catalogue)
        },
        data = d,
        repository = repo,
        entities = ReportingEntities("company", fundIds, start),
        accountingClock = AccountingClock.bank(clock),
        clock = clock,
    )

    @Test
    fun `assemble, approve by a second person, submit`(): Unit = runBlocking {
        val svc = service()
        val assembled = svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker")
        assertThat(assembled.revision).isEqualTo(1)
        val approved = svc.approve(assembled.id, "checker")
        val submitted = svc.submit(approved.id, "REF-1", "checker")
        assertThat(submitted.status).isEqualTo(ReturnStatus.SUBMITTED)
        assertThat(repo.rows.getValue(assembled.id).submissionReference).isEqualTo("REF-1")
    }

    @Test
    fun `the assembler cannot approve through the service`(): Unit = runBlocking {
        val svc = service()
        val assembled = svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker")
        assertThatThrownBy {
            runBlocking { svc.approve(assembled.id, "maker") }
        }.isInstanceOf(TaxConflictException::class.java)
        assertThat(repo.rows.getValue(assembled.id).status).isEqualTo(ReturnStatus.ASSEMBLED)
    }

    @Test
    fun `a running period cannot be assembled`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service().assemble("test-cat", "M-FUND", "fund-a", "2026-08", "maker") } }
            .isInstanceOf(TaxConflictException::class.java).hasMessageContaining("has not ended")
    }

    @Test
    fun `an undeclared entity or a company code against a fund return is refused`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service().assemble("test-cat", "M-FUND", "company", "2026-07", "maker") } }
            .isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `an unbound data source refuses instead of filing zeroes`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service(d = FixedData(null)).assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker") }
        }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
        assertThat(repo.rows).isEmpty()
    }

    @Test
    fun `an open revision blocks re-assembly, a submitted one allows a correction revision`(): Unit = runBlocking {
        val svc = service()
        val first = svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker")
        assertThatThrownBy { runBlocking { svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker") } }
            .isInstanceOf(TaxConflictException::class.java).hasMessageContaining("revision 1")
        svc.submit(svc.approve(first.id, "checker").id, "REF-1", "checker")
        val correction = svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker")
        assertThat(correction.revision).isEqualTo(2)
    }

    @Test
    fun `breaches count never-assembled returns from the reporting start`(): Unit = runBlocking {
        val breaches = service(start = LocalDate.of(2026, 6, 30)).breaches()
        // M-FUND: June (due 20 Jul) and July (due 20 Aug) x 2 funds; Q-CO: Q2 (due 30 Jul) x company.
        assertThat(
            breaches.map {
                "${it.returnCode}/${it.entityId}/${it.period}/${it.kind}"
            },
        ).containsExactlyInAnyOrder(
            "M-FUND/fund-a/2026-06/NOT_ASSEMBLED",
            "M-FUND/fund-a/2026-07/NOT_ASSEMBLED",
            "M-FUND/fund-b/2026-06/NOT_ASSEMBLED",
            "M-FUND/fund-b/2026-07/NOT_ASSEMBLED",
            "Q-CO/company/2026-Q2/NOT_ASSEMBLED",
        )
    }

    @Test
    fun `an assembled but unsubmitted return past its deadline is a breach until submitted`(): Unit = runBlocking {
        val svc = service()
        val ret = svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker")
        assertThat(svc.breaches().map { it.kind }).containsExactly(BreachKind.NOT_SUBMITTED)
        svc.submit(svc.approve(ret.id, "checker").id, "REF", "checker")
        assertThat(svc.breaches()).isEmpty()
    }

    @Test
    fun `an overdue correction remains a breach after an earlier revision was submitted`(): Unit = runBlocking {
        val svc = service()
        val original = svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker")
        svc.submit(svc.approve(original.id, "checker").id, "REF-1", "checker")
        val correction = svc.assemble("test-cat", "M-FUND", "fund-a", "2026-07", "maker")
        assertThat(svc.breaches().map { "${it.period}/${it.kind}" })
            .containsExactly("2026-07/NOT_SUBMITTED")
        svc.submit(svc.approve(correction.id, "checker").id, "REF-2", "checker")
        assertThat(svc.breaches()).isEmpty()
    }

    @Test
    fun `missing reporting start or fund roster refuses deadline status instead of zero`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service(start = null).breaches() } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("reporting start")
        assertThatThrownBy { runBlocking { service(fundIds = emptyList()).breaches() } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
            .hasMessageContaining("fund roster")
    }

    @Test
    fun `the scheduler publishes the breach count to the gauge`(): Unit = runBlocking {
        var published = -1
        val metrics = object : StatutoryReturnMetricsPort {
            override fun recordBreaches(count: Int) {
                published = count
            }
        }
        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val domainMetrics = mockk<DomainMetrics> {
            every { registerWorkflowLiveness(any(), any()) } returns liveness
        }
        StatutoryReturnDeadlineScheduler(service(start = LocalDate.of(2026, 6, 30)), metrics, domainMetrics)
            .also { it.registerLiveness() }
            .refresh()
        assertThat(published).isEqualTo(5)
    }

    @Test
    fun `the scheduler cannot record success or zero breaches without reporting configuration`(): Unit = runBlocking {
        var published = -1
        val metrics = object : StatutoryReturnMetricsPort {
            override fun recordBreaches(count: Int) {
                published = count
            }
        }
        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val domainMetrics = mockk<DomainMetrics> {
            every { registerWorkflowLiveness(any(), any()) } returns liveness
        }
        val scheduler = StatutoryReturnDeadlineScheduler(service(start = null), metrics, domainMetrics)
            .also { it.registerLiveness() }

        assertThatThrownBy { runBlocking { scheduler.refresh() } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
        assertThat(published).isEqualTo(-1)
        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
