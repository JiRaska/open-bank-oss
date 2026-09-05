// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.ListImportResult
import com.openbank.sanctions.domain.model.SanctionsList
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.UpdateSanctionsListRequest
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsListRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

/**
 * Covers cron validation branches, the refresh/refreshAll fan-out, and the scheduled-refresh
 * due-check — all pure/mockable logic that does not require a real datasource.
 */
class SanctionsListServiceTest {

    private val repo = mockk<SanctionsListRepositoryImpl>()
    private val importer = mockk<SanctionsImportService>()
    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val service = SanctionsListService(repo, importer, clock)

    // ──── listAll / getById ─────────────────────────────────────────────────

    @Test
    fun `listAll delegates to repo`(): Unit = runBlocking {
        val expected = listOf(sampleList())
        coEvery { repo.listSanctionsLists() } returns expected

        assertThat(service.listAll()).isEqualTo(expected)
    }

    @Test
    fun `getById delegates to repo`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val expected = sampleList(id = id)
        coEvery { repo.findSanctionsListById(id) } returns expected

        assertThat(service.getById(id)).isEqualTo(expected)
    }

    // ──── update — cron validation ──────────────────────────────────────────

    @Test
    fun `update normalizes cronDays and trims sourceUrl`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val expected = sampleList(id = id)
        coEvery {
            repo.updateSanctionsList(id, true, "https://example.com/feed", 6, 30, "MON,TUE")
        } returns expected

        val result = service.update(
            id,
            UpdateSanctionsListRequest(
                enabled = true,
                sourceUrl = "  https://example.com/feed  ",
                cronHour = 6,
                cronMinute = 30,
                cronDays = " mon,, tue,mon ",
            ),
        )

        assertThat(result).isEqualTo(expected)
        coVerify { repo.updateSanctionsList(id, true, "https://example.com/feed", 6, 30, "MON,TUE") }
    }

    @Test
    fun `update maps blank sourceUrl to null`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.updateSanctionsList(id, null, null, null, null, null) } returns sampleList(id = id)

        service.update(id, UpdateSanctionsListRequest(null, "   ", null, null, null))

        coVerify { repo.updateSanctionsList(id, null, null, null, null, null) }
    }

    @Test
    fun `update returns null when repo finds nothing`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.updateSanctionsList(id, null, null, null, null, null) } returns null

        assertThat(service.update(id, UpdateSanctionsListRequest(null, null, null, null, null))).isNull()
    }

    @Test
    fun `update rejects cronHour out of range`(): Unit = runBlocking {
        val id = UUID.randomUUID()

        assertThatThrownBy {
            runBlocking { service.update(id, UpdateSanctionsListRequest(null, null, 24, null, null)) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cronHour must be between 0 and 23")
    }

    @Test
    fun `update rejects cronMinute out of range`(): Unit = runBlocking {
        val id = UUID.randomUUID()

        assertThatThrownBy {
            runBlocking { service.update(id, UpdateSanctionsListRequest(null, null, null, 60, null)) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cronMinute must be between 0 and 59")
    }

    @Test
    fun `update rejects unsupported cronDays token`(): Unit = runBlocking {
        val id = UUID.randomUUID()

        assertThatThrownBy {
            runBlocking { service.update(id, UpdateSanctionsListRequest(null, null, null, null, "FUNDAY")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cronDays contains unsupported day value")
    }

    @Test
    fun `update rejects cronDays that normalizes to empty`(): Unit = runBlocking {
        val id = UUID.randomUUID()

        assertThatThrownBy {
            runBlocking { service.update(id, UpdateSanctionsListRequest(null, null, null, null, " , , ")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cronDays must contain at least one valid day")
    }

    @Test
    fun `update allows null cronDays (no change)`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.updateSanctionsList(id, null, null, null, null, null) } returns sampleList(id = id)

        service.update(id, UpdateSanctionsListRequest(null, null, null, null, null))

        coVerify { repo.updateSanctionsList(id, null, null, null, null, null) }
    }

    // ──── refresh ────────────────────────────────────────────────────────────

    @Test
    fun `refresh throws NotFoundException when list is unknown`(): Unit = runBlocking {
        coEvery { repo.findByListType("OFAC_SDN") } returns null

        assertThatThrownBy {
            runBlocking { service.refresh("OFAC_SDN") }
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `refresh uses importer count when import succeeds`(): Unit = runBlocking {
        val list = sampleList(listType = "OFAC_SDN", sourceUrl = "https://example.com/sdn.xml")
        coEvery { repo.findByListType("OFAC_SDN") } returns list
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, "https://example.com/sdn.xml") } returns
            ListImportResult.imported(42)
        coEvery { repo.markUpdated("OFAC_SDN", 42) } returns list.copy(lastEntryCount = 42)

        val result = service.refresh("OFAC_SDN")

        assertThat(result.lastEntryCount).isEqualTo(42)
        coVerify { repo.markUpdated("OFAC_SDN", 42) }
    }

    @Test
    fun `refresh falls back to stored count when importer returns zero`(): Unit = runBlocking {
        val list = sampleList(listType = "FATF_HIGH_RISK", lastEntryCount = 7)
        coEvery { repo.findByListType("FATF_HIGH_RISK") } returns list
        coEvery { importer.importList(SanctionsListType.FATF_HIGH_RISK, any()) } returns
            ListImportResult.skippedNotEntityBased("FATF is country-risk")
        coEvery { repo.markUpdated("FATF_HIGH_RISK", 7) } returns list

        service.refresh("FATF_HIGH_RISK")

        coVerify { repo.markUpdated("FATF_HIGH_RISK", 7) }
    }

    @Test
    fun `refresh falls back to zero when stored count is null and importer returns zero`(): Unit = runBlocking {
        val list = sampleList(listType = "CNB_DOMESTIC", lastEntryCount = null)
        coEvery { repo.findByListType("CNB_DOMESTIC") } returns list
        coEvery { importer.importList(SanctionsListType.CNB_DOMESTIC, any()) } returns
            ListImportResult.skippedNotEntityBased("CNB has no feed")
        coEvery { repo.markUpdated("CNB_DOMESTIC", 0) } returns list

        service.refresh("CNB_DOMESTIC")

        coVerify { repo.markUpdated("CNB_DOMESTIC", 0) }
    }

    @Test
    fun `refresh uses stored count directly for an unrecognized listType enum`(): Unit = runBlocking {
        val list = sampleList(listType = "NOT_A_REAL_TYPE", lastEntryCount = 3)
        coEvery { repo.findByListType("NOT_A_REAL_TYPE") } returns list
        coEvery { repo.markUpdated("NOT_A_REAL_TYPE", 3) } returns list

        service.refresh("NOT_A_REAL_TYPE")

        coVerify(exactly = 0) { importer.importList(any(), any()) }
        coVerify { repo.markUpdated("NOT_A_REAL_TYPE", 3) }
    }

    @Test
    fun `refresh throws IllegalStateException when markUpdated fails to persist`(): Unit = runBlocking {
        val list = sampleList(listType = "OFAC_SDN")
        coEvery { repo.findByListType("OFAC_SDN") } returns list
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, any()) } returns ListImportResult.imported(5)
        coEvery { repo.markUpdated("OFAC_SDN", 5) } returns null

        assertThatThrownBy {
            runBlocking { service.refresh("OFAC_SDN") }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to persist sanctions list refresh")
    }

    // ──── refreshAll ─────────────────────────────────────────────────────────

    @Test
    fun `refreshAll only refreshes enabled lists`(): Unit = runBlocking {
        val enabled = sampleList(listType = "OFAC_SDN", enabled = true)
        val disabled = sampleList(listType = "FATF_HIGH_RISK", enabled = false)
        coEvery { repo.listSanctionsLists() } returns listOf(enabled, disabled)
        coEvery { repo.findByListType("OFAC_SDN") } returns enabled
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, any()) } returns ListImportResult.imported(1)
        coEvery { repo.markUpdated("OFAC_SDN", 1) } returns enabled.copy(lastEntryCount = 1)

        val result = service.refreshAll()

        assertThat(result).hasSize(1)
        coVerify(exactly = 0) { repo.findByListType("FATF_HIGH_RISK") }
    }

    @Test
    fun `refreshAll returns empty list when nothing is enabled`(): Unit = runBlocking {
        coEvery { repo.listSanctionsLists() } returns listOf(sampleList(enabled = false))

        assertThat(service.refreshAll()).isEmpty()
    }

    // ──── scheduledRefresh ──────────────────────────────────────────────────

    @Test
    fun `scheduledRefresh skips lists whose cron schedule does not match now`(): Unit = runBlocking {
        // clock fixed at 2024-01-15T12:00:00Z (a Monday, UTC); list scheduled for hour 6 never matches
        val notDue = sampleList(
            listType = "OFAC_SDN",
            cronHour = 6,
            cronMinute = 0,
            cronDays = "MON,TUE,WED,THU,FRI",
        )
        coEvery { repo.listSanctionsLists() } returns listOf(notDue)

        service.scheduledRefresh()

        coVerify(exactly = 0) { repo.findByListType(any()) }
        coVerify(exactly = 0) { importer.importList(any(), any()) }
    }

    @Test
    fun `scheduledRefresh imports a due list via the real importer and persists the count`(): Unit = runBlocking {
        // clock fixed at 2024-01-15T12:00:00Z (a Monday, UTC) — matches this list's cron exactly
        val due = sampleList(
            listType = "OFAC_SDN",
            sourceUrl = "https://example.com/sdn.xml",
            cronHour = 12,
            cronMinute = 0,
            cronDays = "MON,TUE,WED,THU,FRI",
        )
        coEvery { repo.listSanctionsLists() } returns listOf(due)
        coEvery { repo.findByListType("OFAC_SDN") } returns due
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, "https://example.com/sdn.xml") } returns
            ListImportResult.imported(123)
        coEvery { repo.markUpdated("OFAC_SDN", 123) } returns due.copy(lastEntryCount = 123)

        service.scheduledRefresh()

        coVerify { importer.importList(SanctionsListType.OFAC_SDN, "https://example.com/sdn.xml") }
        coVerify { repo.markUpdated("OFAC_SDN", 123) }
    }

    @Test
    fun `scheduledRefresh logs and continues when one due list fails to refresh`(): Unit = runBlocking {
        val failing = sampleList(listType = "OFAC_SDN", cronHour = 12, cronMinute = 0)
        val healthy = sampleList(listType = "EU_CONSOLIDATED", cronHour = 12, cronMinute = 0)
        coEvery { repo.listSanctionsLists() } returns listOf(failing, healthy)
        coEvery { repo.findByListType("OFAC_SDN") } returns failing
        coEvery { importer.importList(SanctionsListType.OFAC_SDN, any()) } throws
            IllegalStateException("boom")
        coEvery { repo.findByListType("EU_CONSOLIDATED") } returns healthy
        coEvery { importer.importList(SanctionsListType.EU_CONSOLIDATED, any()) } returns ListImportResult.imported(5)
        coEvery { repo.markUpdated("EU_CONSOLIDATED", 5) } returns healthy.copy(lastEntryCount = 5)

        service.scheduledRefresh()

        coVerify { repo.markUpdated("EU_CONSOLIDATED", 5) }
        coVerify(exactly = 0) { repo.markUpdated("OFAC_SDN", any()) }
    }

    // ──── scheduledRefresh — the same-minute de-duplication is zone-consistent (#2963) ─────────
    //
    // These two are the falsifying pair for the ZoneId.systemDefault() removal. They FORCE the JVM
    // default zone to Europe/Prague, because the defect was invisible on a UTC host — and the
    // production container is UTC, which is why nothing ever caught it. `now` comes from the
    // injected clock (UTC here); `lastRunAt` used to come from the host default. On a +01:00 host
    // the comparison was wrong in both directions, so the two tests below assert both. Each fails
    // against the pre-#2963 code and passes after it; neither can pass by accident, since the
    // assertion is on whether the importer ran at all.
    //
    // The default zone is restored in a finally block — leaving it set would silently retune every
    // later test in this JVM.

    private fun <T> withDefaultZone(zone: String, body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return try {
            body()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `scheduledRefresh skips a list already refreshed this minute even off-UTC`() {
        withDefaultZone("Europe/Prague") {
            runBlocking {
                // clock is fixed at 2024-01-15T12:00:00Z; this list is due at 12:00 and ALREADY ran
                // at exactly that instant, so the same-minute guard must suppress it. Read in the
                // host default the last run reads 13:00 local, which does not equal 12:00 UTC, and
                // the guard used to conclude the list had never run.
                val due = sampleList(
                    listType = "OFAC_SDN",
                    sourceUrl = "https://example.com/sdn.xml",
                    cronHour = 12,
                    cronMinute = 0,
                ).copy(lastUpdatedAt = Instant.parse("2024-01-15T12:00:00Z"))
                coEvery { repo.listSanctionsLists() } returns listOf(due)
                // The refresh path is fully stubbed so that a wrongly-due list would SUCCEED. Left
                // unstubbed, mockk throws inside refresh(), scheduledRefresh() swallows it, and
                // `importList` is never reached — so the assertion below would hold for the wrong
                // reason and pass against the very code it exists to reject.
                coEvery { repo.findByListType("OFAC_SDN") } returns due
                coEvery {
                    importer.importList(SanctionsListType.OFAC_SDN, "https://example.com/sdn.xml")
                } returns ListImportResult.imported(7)
                coEvery { repo.markUpdated("OFAC_SDN", 7) } returns due.copy(lastEntryCount = 7)

                service.scheduledRefresh()

                coVerify(exactly = 0) { importer.importList(any(), any()) }
            }
        }
    }

    @Test
    fun `scheduledRefresh still refreshes when the previous run was an hour earlier off-UTC`() {
        withDefaultZone("Europe/Prague") {
            runBlocking {
                // Previous run at 11:00Z is a DIFFERENT minute from now (12:00Z), so the list is
                // due. Read in the host default 11:00Z is 12:00 local — which the old comparison
                // matched against 12:00 UTC and used to skip a refresh that was genuinely owed.
                val due = sampleList(
                    listType = "OFAC_SDN",
                    sourceUrl = "https://example.com/sdn.xml",
                    cronHour = 12,
                    cronMinute = 0,
                ).copy(lastUpdatedAt = Instant.parse("2024-01-15T11:00:00Z"))
                coEvery { repo.listSanctionsLists() } returns listOf(due)
                coEvery { repo.findByListType("OFAC_SDN") } returns due
                coEvery {
                    importer.importList(SanctionsListType.OFAC_SDN, "https://example.com/sdn.xml")
                } returns ListImportResult.imported(7)
                coEvery { repo.markUpdated("OFAC_SDN", 7) } returns due.copy(lastEntryCount = 7)

                service.scheduledRefresh()

                coVerify { importer.importList(SanctionsListType.OFAC_SDN, "https://example.com/sdn.xml") }
            }
        }
    }

    private fun sampleList(
        id: UUID = UUID.randomUUID(),
        listType: String = "OFAC_SDN",
        enabled: Boolean = true,
        sourceUrl: String = "https://example.com/feed",
        lastEntryCount: Int? = null,
        cronHour: Int = 6,
        cronMinute: Int = 0,
        cronDays: String = "MON,TUE,WED,THU,FRI",
    ) = SanctionsList(
        id = id,
        listType = listType,
        displayName = listType,
        sourceUrl = sourceUrl,
        enabled = enabled,
        lastUpdatedAt = null,
        lastEntryCount = lastEntryCount,
        cronHour = cronHour,
        cronMinute = cronMinute,
        cronDays = cronDays,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
