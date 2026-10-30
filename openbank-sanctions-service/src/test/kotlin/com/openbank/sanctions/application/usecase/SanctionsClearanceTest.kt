// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.application.port.out.SanctionsRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MatchType
import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsEntryMatch
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.SanctionsMatch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SanctionsClearanceTest {
    private val repo = mockk<SanctionsRepository>()
    private val entryRepo = mockk<SanctionsEntryRepository>()
    private val importer = mockk<SanctionsImportService>()
    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val service = SanctionsService(repo, entryRepo, importer, Clock.fixed(now, ZoneOffset.UTC))
    private val subjectId = UUID.randomUUID()

    init {
        every { importer.normalizeForSearch(any()) } answers { firstArg<String>().lowercase() }
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }
        coEvery { repo.findWhitelistsForSubject(any()) } returns emptyList()
    }

    @Test
    fun `a new attempt is whitelisted only after fresh screening finds the reviewed match`(): Unit = runBlocking {
        val reviewed = whitelist(check(status = SanctionsCheckStatus.POTENTIAL_HIT))
        currentMatches(match("ofac-1", 0.70))

        val result = service.screen(screenCommand(idempotencyKey = "new-account-attempt"))

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.WHITELISTED)
        assertThat(result.id).isNotEqualTo(reviewed.id)
        assertThat(result.checkedAt).isEqualTo(now)
        assertThat(result.reviewedBy).isEqualTo("analyst-1")
        assertThat(result.clearanceExpiresAt).isEqualTo(reviewed.clearanceExpiresAt)
        coVerify(exactly = 1) { entryRepo.search("john doe", any(), any(), any()) }
    }

    @Test
    fun `a new attempt after a previous CLEAR screens current list entries again`(): Unit = runBlocking {
        currentMatches(match("new-list-entry", 0.96))

        val result = service.screen(screenCommand(idempotencyKey = "after-list-refresh"))

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.HIT)
        assertThat(result.matches.map { it.matchedId }).containsExactly("new-list-entry")
    }

    @Test
    fun `a clearance does not cover an additional current hit`(): Unit = runBlocking {
        whitelist(check(status = SanctionsCheckStatus.POTENTIAL_HIT))
        currentMatches(match("ofac-1", 0.70), match("ofac-2", 0.96))

        val result = service.screen(screenCommand())

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.HIT)
    }

    @Test
    fun `a clearance does not cover a changed subject with the same party id`(): Unit = runBlocking {
        whitelist(check(status = SanctionsCheckStatus.POTENTIAL_HIT))
        currentMatches(match("ofac-1", 0.70))

        val result = service.screen(screenCommand(name = "Different Person"))

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
    }

    @Test
    fun `an expired clearance does not permit opening`(): Unit = runBlocking {
        val expired = check(status = SanctionsCheckStatus.WHITELISTED).copy(
            reviewedBy = "analyst-1",
            reviewedAt = now.minusSeconds(3600),
            clearanceExpiresAt = now.minusSeconds(1),
        )
        coEvery { repo.findWhitelistsForSubject(subjectId.toString()) } returns listOf(expired)
        currentMatches(match("ofac-1", 0.70))

        val result = service.screen(screenCommand())

        assertThat(result.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
    }

    @Test
    fun `whitelisting requires an explicit subject identity`(): Unit = runBlocking {
        val legacy = check(status = SanctionsCheckStatus.POTENTIAL_HIT).copy(subjectId = null)
        coEvery { repo.findById(legacy.id) } returns legacy

        assertThatThrownBy {
            runBlocking {
                service.review(
                    ReviewCommand(legacy.id, "analyst-1", "false positive", SanctionsCheckStatus.WHITELISTED),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("subject")
        coVerify(exactly = 0) { repo.updateWithEvent(any(), any()) }
    }

    private fun whitelist(clearance: SanctionsCheck): SanctionsCheck {
        coEvery { repo.findById(clearance.id) } returns clearance
        coEvery { repo.updateWithEvent(any(), any()) } answers { firstArg() }
        val reviewed = runBlocking {
            service.review(
                ReviewCommand(clearance.id, "analyst-1", "false positive", SanctionsCheckStatus.WHITELISTED),
            )
        }
        coEvery { repo.findWhitelistsForSubject(subjectId.toString()) } returns listOf(reviewed)
        return reviewed
    }

    private fun currentMatches(vararg matches: SanctionsMatch) {
        coEvery { entryRepo.search(any(), any(), any(), any()) } answers {
            val query = firstArg<String>()
            matches.map { current ->
                val entry = entry(current.matchedId, current.matchedName)
                SanctionsEntryMatch(entry = entry, matchedName = query, score = current.matchScore)
            }
        }
    }

    private fun screenCommand(idempotencyKey: String = "retry-with-new-key", name: String = "John Doe") =
        ScreenEntityCommand(
            idempotencyKey = idempotencyKey,
            entityType = EntityType.INDIVIDUAL,
            name = name,
            subjectId = subjectId.toString(),
        )

    private fun check(status: SanctionsCheckStatus) = SanctionsCheck(
        id = UUID.randomUUID(),
        idempotencyKey = "original-account-attempt",
        entityType = EntityType.INDIVIDUAL,
        name = "John Doe",
        aliases = emptyList(),
        dateOfBirth = null,
        nationality = null,
        identifiers = emptyMap(),
        status = status,
        matches = listOf(match("ofac-1", 0.70)),
        overallScore = 0.70,
        checkedLists = SanctionsListType.entries,
        reviewedBy = null,
        reviewNote = null,
        checkedAt = now.minusSeconds(3600),
        reviewedAt = null,
        subjectId = subjectId.toString(),
    )

    private fun match(id: String, score: Double) = SanctionsMatch(
        listType = SanctionsListType.OFAC_SDN,
        matchType = MatchType.FUZZY,
        matchScore = score,
        matchedName = "Sanctioned Person",
        matchedId = id,
        listEntryDate = null,
        programs = listOf("TEST"),
    )

    private fun entry(id: String?, name: String) = SanctionsEntry(
        id = UUID.randomUUID(),
        listType = SanctionsListType.OFAC_SDN,
        externalId = id,
        entityType = EntityType.INDIVIDUAL,
        primaryName = name,
        programs = listOf("TEST"),
        searchText = name.lowercase(),
        createdAt = now.minusSeconds(7200),
        updatedAt = now,
    )
}
