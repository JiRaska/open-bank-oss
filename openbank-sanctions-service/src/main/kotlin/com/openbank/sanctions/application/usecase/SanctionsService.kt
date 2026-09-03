// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.`in`.*
import com.openbank.sanctions.application.port.out.*
import com.openbank.sanctions.domain.model.*
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SanctionsService(
    private val repo: SanctionsRepository,
    private val entryRepo: SanctionsEntryRepository,
    private val importer: SanctionsImportService,
    private val clock: Clock,
) : SanctionsUseCase {

    /** Map optional listTypes strings from the command to enum values.
     *  Unknown strings are silently dropped; null/empty → all known list types. */
    private fun resolveTargetLists(cmd: ScreenEntityCommand): List<SanctionsListType> = cmd.listTypes
        ?.mapNotNull { raw -> runCatching { SanctionsListType.valueOf(raw) }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?: SanctionsListType.entries

    /**
     * Query [SanctionsEntryRepository] using pg_trgm similarity for each name in the command.
     * Returns (status, matches) based on DB results — no hardcoded data.
     */
    private suspend fun performScreening(
        cmd: ScreenEntityCommand,
        targetLists: List<SanctionsListType>,
    ): Pair<SanctionsCheckStatus, List<SanctionsMatch>> {
        val allNames = listOf(cmd.name) + cmd.aliases.filterNotNull()
        val matchMap = mutableMapOf<String, SanctionsEntryMatch>() // key=listType+externalId, keep best

        for (name in allNames) {
            val normalized = importer.normalizeForSearch(name)
            val results = entryRepo.search(
                normalizedQuery = normalized,
                listTypes = targetLists,
                threshold = HIT_THRESHOLD,
                limit = 10,
            )
            for (m in results) {
                val key = "${m.entry.listType}:${m.entry.externalId ?: m.entry.primaryName}"
                val existing = matchMap[key]
                if (existing == null || m.score > existing.score) matchMap[key] = m
            }
        }

        val hits = matchMap.values.sortedByDescending { it.score }.map { m ->
            SanctionsMatch(
                listType = m.entry.listType,
                matchType = when {
                    m.score >= 0.95 -> MatchType.EXACT
                    m.score >= 0.80 -> MatchType.FUZZY
                    else -> MatchType.PHONETIC
                },
                matchScore = m.score,
                matchedName = m.entry.primaryName,
                matchedId = m.entry.externalId,
                listEntryDate = null,
                programs = m.entry.programs,
            )
        }

        val status = when {
            hits.any { it.matchScore >= HIT_THRESHOLD } -> SanctionsCheckStatus.HIT
            hits.any { it.matchScore >= POTENTIAL_HIT_THRESHOLD } -> SanctionsCheckStatus.POTENTIAL_HIT
            else -> SanctionsCheckStatus.CLEAR
        }
        return status to hits
    }

    override suspend fun screen(cmd: ScreenEntityCommand): SanctionsCheck {
        // #7867: reject a null JSON array element with a 400 (IllegalArgumentException) before
        // any dereference turns it into an NPE-driven 500. Jackson does not null-check
        // collection elements, so `[null]` reaches here despite the Kotlin element type.
        cmd.aliases.forEachIndexed { index, alias ->
            requireNotNull(alias) { "aliases[$index] must not be null" }
        }
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        val targetLists = resolveTargetLists(cmd)
        val (status, matches) = performScreening(cmd, targetLists)
        val check = SanctionsCheck(
            id = UUID.randomUUID(), idempotencyKey = cmd.idempotencyKey,
            entityType = cmd.entityType, name = cmd.name, aliases = cmd.aliases.filterNotNull(),
            dateOfBirth = cmd.dateOfBirth, nationality = cmd.nationality,
            identifiers = cmd.identifiers, status = status, matches = matches,
            overallScore = matches.maxOfOrNull { it.matchScore } ?: 0.0,
            checkedLists = targetLists,
            reviewedBy = null, reviewNote = null,
            checkedAt = Instant.now(clock), reviewedAt = null,
        )
        return repo.saveWithEvent(check, EVENT_SCREENED)
    }

    companion object {
        private const val HIT_THRESHOLD = 0.85
        private const val POTENTIAL_HIT_THRESHOLD = 0.65

        /**
         * Emitted by [screen]: an automated screening produced a status from list matching.
         *
         * Unchanged on purpose — audit-service already archives this topic, and renaming the
         * existing type would be a breaking change for no gain. The differentiation is achieved
         * by giving the OTHER path its own type, below.
         */
        const val EVENT_SCREENED = "SanctionChecked"

        /**
         * Emitted by [review]: a human analyst manually dispositioned an existing check.
         *
         * These two events are not the same business fact, and a consumer must be able to tell
         * them apart from the envelope alone. Until #1035 they shared `"SanctionChecked"`, so the
         * topic carried two meanings under one name and any consumer switching on `eventType` —
         * which is how every consumer in this fleet dispatches, `PartyEventConsumer` included —
         * could not route them differently.
         *
         * Discriminating on the payload instead is not a substitute: it would mean inferring the
         * path from `reviewedAt != null`, i.e. deriving an event's identity from a nullable
         * aggregate field that stays set forever after the first review. That is the sentinel
         * trap this repo has already paid for elsewhere; the envelope has to carry the fact.
         *
         * Differentiating BEFORE any consumer exists is the whole point: aml-service has no
         * consumer for this topic today (`@Incoming` count in `openbank-aml-service/src/main` is
         * 1, and it is the party-events one), so this change breaks nothing. Once a consumer
         * exists it cannot be retrofitted without a coordinated rollout.
         */
        const val EVENT_REVIEWED = "SanctionReviewed"
    }

    override suspend fun review(cmd: ReviewCommand): SanctionsCheck {
        val check = repo.findById(cmd.checkId) ?: error("Sanctions check not found: ${cmd.checkId}")
        val updated = check.copy(
            status = cmd.newStatus,
            reviewedBy = cmd.reviewedBy,
            reviewNote = cmd.note,
            reviewedAt = Instant.now(clock),
        )
        // updateWithEvent, not saveWithEvent: this check already exists, and the aggregate's id is
        // application-assigned, so the insert path would schedule an INSERT and collide with its
        // own primary key (ADR-0126 D3).
        return repo.updateWithEvent(updated, EVENT_REVIEWED)
    }

    override suspend fun getById(id: UUID) = repo.findById(id)
    override suspend fun listHits() = repo.findByStatus(SanctionsCheckStatus.HIT)
    override suspend fun listPending() = repo.findByStatus(SanctionsCheckStatus.POTENTIAL_HIT)
    override suspend fun listChecks() = repo.listChecks()
}
