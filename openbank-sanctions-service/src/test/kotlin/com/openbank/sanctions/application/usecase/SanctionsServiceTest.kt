// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.application.port.out.SanctionsRepository
import com.openbank.sanctions.domain.model.*
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SanctionsServiceTest {

    private val repo = mockk<SanctionsRepository>()
    private val entryRepo = mockk<SanctionsEntryRepository>()
    private val importer = mockk<SanctionsImportService>()
    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val service = SanctionsService(repo, entryRepo, importer, clock)

    // Sensible default: normalize returns lowercased input; search returns no results
    init {
        every { importer.normalizeForSearch(any()) } answers { firstArg<String>().lowercase() }
        coEvery { entryRepo.search(any(), any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `screen returns existing check for idempotent request`(): Unit = runBlocking {
        val existing = sampleCheck(status = SanctionsCheckStatus.CLEAR)
        coEvery { repo.findByIdempotencyKey("idem-1") } returns existing

        val result = service.screen(sampleScreenCommand(idempotencyKey = "idem-1", name = "John Doe"))

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 1) { repo.findByIdempotencyKey("idem-1") }
        coVerify(exactly = 0) { repo.saveWithEvent(any(), any()) }
    }

    @Test
    fun `screen rejects a null JSON array element with IllegalArgumentException`(): Unit = runBlocking {
        // #7867: Jackson null-checks constructor parameters but not collection elements, so
        // `{"aliases": [null]}` arrives as a list holding a null. The guard must reject it
        // (IllegalArgumentException -> 400) before the first dereference turns it into a 500.
        var thrown: Throwable? = null
        try {
            service.screen(sampleScreenCommand(name = "John Doe", aliases = listOf("JD", null)))
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("aliases[1]")
        coVerify(exactly = 0) { repo.findByIdempotencyKey(any()) }
        coVerify(exactly = 0) { repo.saveWithEvent(any(), any()) }
    }

    @Test
    fun `screen detects HIT when entry repo returns a high-score match`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }

        val entry = sampleEntry(
            listType = SanctionsListType.OFAC_SDN,
            primaryName = "Vladimir Putin",
            programs = listOf("RUSSIA-EO14024"),
            externalId = "ofac-17766",
        )
        coEvery { entryRepo.search("vladimir putin", any(), any(), any()) } returns listOf(
            SanctionsEntryMatch(entry = entry, matchedName = "Vladimir Putin", score = 0.95),
        )

        val result = service.screen(sampleScreenCommand(name = "Vladimir Putin"))

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.HIT)
        assertThat(result.overallScore).isEqualTo(0.95)
        assertThat(result.matches).hasSize(1)
        val match = result.matches.first()
        assertThat(match.matchedName).isEqualTo("Vladimir Putin")
        assertThat(match.matchScore).isEqualTo(0.95)
        assertThat(match.matchType).isEqualTo(MatchType.EXACT)
        assertThat(match.programs).containsExactly("RUSSIA-EO14024")
        coVerify { repo.saveWithEvent(match { it.status == SanctionsCheckStatus.HIT }, eq("SanctionChecked")) }
    }

    @Test
    fun `screen returns CLEAR when entry repo returns no matches`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }
        // entryRepo.search already stubbed to return empty in init block

        val result = service.screen(sampleScreenCommand(name = "Jane Citizen"))

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.CLEAR)
        assertThat(result.overallScore).isEqualTo(0.0)
        assertThat(result.matches).isEmpty()
        coVerify {
            repo.saveWithEvent(
                match { it.status == SanctionsCheckStatus.CLEAR && it.matches.isEmpty() },
                eq("SanctionChecked"),
            )
        }
    }

    @Test
    fun `screen reports POTENTIAL_HIT for mid-score matches`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }

        val entry = sampleEntry(listType = SanctionsListType.PEP_GLOBAL, primaryName = "Andrej Babiš")
        coEvery { entryRepo.search("andrej babis", any(), any(), any()) } returns listOf(
            SanctionsEntryMatch(entry = entry, matchedName = "Andrej Babiš", score = 0.70),
        )

        val result = service.screen(sampleScreenCommand(name = "Andrej Babis"))

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
        assertThat(result.matches).hasSize(1)
        assertThat(result.matches.first().matchType).isEqualTo(MatchType.PHONETIC)
    }

    @Test
    fun `screen checks aliases — hit via alias search wins`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }

        // Primary "john doe" → no hits; alias "putin jr." → hit
        val entry = sampleEntry(listType = SanctionsListType.OFAC_SDN, primaryName = "Vladimir Putin")
        coEvery { entryRepo.search("putin jr.", any(), any(), any()) } returns listOf(
            SanctionsEntryMatch(entry = entry, matchedName = "Vladimir Putin", score = 0.88),
        )

        val result = service.screen(
            sampleScreenCommand(name = "John Doe", aliases = listOf("Mr. Nobody", "Putin Jr.")),
        )

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.HIT)
        assertThat(result.matches).hasSize(1)
        assertThat(result.matches.first().matchedName).isEqualTo("Vladimir Putin")
    }

    @Test
    fun `screen deduplicates hits from same entry across multiple names`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }

        val entry = sampleEntry(
            listType = SanctionsListType.OFAC_SDN,
            primaryName = "Vladimir Putin",
            externalId = "ofac-17766",
        )
        // Both primary and alias resolve to the same entry — only one SanctionsMatch expected
        coEvery { entryRepo.search("putin", any(), any(), any()) } returns listOf(
            SanctionsEntryMatch(entry = entry, matchedName = "Vladimir Putin", score = 0.90),
        )
        coEvery { entryRepo.search("vlad", any(), any(), any()) } returns listOf(
            SanctionsEntryMatch(entry = entry, matchedName = "Vladimir Putin", score = 0.60),
        )

        val result = service.screen(sampleScreenCommand(name = "Putin", aliases = listOf("Vlad")))

        assertThat(result.matches).hasSize(1) // deduped by listType+externalId
        assertThat(result.matches.first().matchScore).isEqualTo(0.90) // best score wins
    }

    @Test
    fun `review updates status and reviewer`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val existing = sampleCheck(id = id, status = SanctionsCheckStatus.HIT)
        coEvery { repo.findById(id) } returns existing
        coEvery { repo.updateWithEvent(any(), any()) } answers { firstArg() }

        val result = service.review(
            ReviewCommand(
                checkId = id,
                reviewedBy = "analyst-1",
                note = "cleared after manual review",
                newStatus = SanctionsCheckStatus.CLEAR,
            ),
        )

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.CLEAR)
        assertThat(result.reviewedBy).isEqualTo("analyst-1")
        assertThat(result.reviewNote).isEqualTo("cleared after manual review")
        assertThat(result.reviewedAt).isNotNull
        // The UPDATE path specifically. Asserting only "some save was called" is what let this
        // test pass for the whole life of the service while every real review 500'd on
        // `sanctions_checks_pkey` — a mock cannot see Hibernate's INSERT-vs-UPDATE decision, so
        // pinning WHICH port method is called is the only thing this layer can contribute.
        // SanctionsReviewUpdateIT owns the behaviour itself, against a real database.
        coVerify {
            repo.updateWithEvent(
                match { it.status == SanctionsCheckStatus.CLEAR && it.reviewedBy == "analyst-1" },
                // Was `eq("SanctionChecked")` — the same literal the screening path asserts, so
                // this assertion actively ENCODED the defect #1035 reports: it passed precisely
                // because the two paths were indistinguishable, and would have gone red at the
                // fix. Replaced, not deleted; the differentiation itself is pinned by
                // `screen and review emit distinct event types` below.
                eq("SanctionReviewed"),
            )
        }
        coVerify(exactly = 0) { repo.saveWithEvent(any(), any()) }
    }

    /**
     * The screening path and the analyst-review path must not put the same `eventType` on the
     * wire (#1035).
     *
     * Written to FAIL against `origin/main`, where both emitted `"SanctionChecked"`: it captures
     * the literal each path actually passes and asserts they differ. Asserting each path's
     * literal separately cannot do this job — two such assertions stay green when someone later
     * collapses the types back together, because neither one knows about the other.
     */
    @Test
    fun `screen and review emit distinct event types`(): Unit = runBlocking {
        val screened = slot<String>()
        val reviewed = slot<String>()
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), capture(screened)) } answers { firstArg() }

        service.screen(sampleScreenCommand(name = "Jane Citizen"))

        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns sampleCheck(id = id, status = SanctionsCheckStatus.HIT)
        coEvery { repo.updateWithEvent(any(), capture(reviewed)) } answers { firstArg() }

        service.review(
            ReviewCommand(
                checkId = id,
                reviewedBy = "analyst-1",
                note = "cleared after manual review",
                newStatus = SanctionsCheckStatus.CLEAR,
            ),
        )

        assertThat(screened.captured)
            .describedAs("screening and analyst review must be distinguishable from the envelope alone")
            .isNotEqualTo(reviewed.captured)
    }

    @Test
    fun `review throws when check not found`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        assertThatThrownBy {
            runBlocking {
                service.review(
                    ReviewCommand(
                        checkId = id,
                        reviewedBy = "analyst-1",
                        note = "missing",
                        newStatus = SanctionsCheckStatus.CLEAR,
                    ),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Sanctions check not found")
    }

    @Test
    fun `getById delegates to repo`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val expected = sampleCheck(id = id)
        coEvery { repo.findById(id) } returns expected

        assertThat(service.getById(id)).isEqualTo(expected)
        coVerify(exactly = 1) { repo.findById(id) }
    }

    @Test
    fun `listHits delegates to repo`(): Unit = runBlocking {
        val expected = listOf(sampleCheck(status = SanctionsCheckStatus.HIT))
        coEvery { repo.findByStatus(SanctionsCheckStatus.HIT) } returns expected

        assertThat(service.listHits()).isEqualTo(expected)
        coVerify(exactly = 1) { repo.findByStatus(SanctionsCheckStatus.HIT) }
    }

    // ──── helpers ────────────────────────────────────────────────────────────

    private fun sampleScreenCommand(
        idempotencyKey: String = "idem-1",
        name: String = "John Doe",
        aliases: List<String?> = emptyList(),
    ) = ScreenEntityCommand(
        idempotencyKey = idempotencyKey,
        entityType = EntityType.INDIVIDUAL,
        name = name,
        aliases = aliases,
    )

    private fun sampleEntry(
        listType: SanctionsListType = SanctionsListType.OFAC_SDN,
        primaryName: String = "Test Entity",
        externalId: String? = "ext-1",
        programs: List<String> = listOf("TEST"),
    ) = SanctionsEntry(
        id = UUID.randomUUID(),
        listType = listType,
        externalId = externalId,
        entityType = EntityType.INDIVIDUAL,
        primaryName = primaryName,
        programs = programs,
        searchText = primaryName.lowercase(),
    )

    private fun sampleCheck(
        id: UUID = UUID.randomUUID(),
        status: SanctionsCheckStatus = SanctionsCheckStatus.CLEAR,
        reviewedBy: String? = null,
        reviewNote: String? = null,
    ) = SanctionsCheck(
        id = id, idempotencyKey = "idem-1", entityType = EntityType.INDIVIDUAL,
        name = "John Doe", aliases = emptyList(), dateOfBirth = null, nationality = null,
        identifiers = emptyMap(), status = status, matches = emptyList(), overallScore = 0.0,
        checkedLists = SanctionsListType.entries, reviewedBy = reviewedBy,
        reviewNote = reviewNote, checkedAt = Instant.parse("2026-01-01T00:00:00Z"), reviewedAt = null,
    )
}
