// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.sanctions.application.port.`in`.MonitoredSubjectRegistration
import com.openbank.sanctions.application.port.`in`.MonitoredSubjectRequest
import com.openbank.sanctions.application.port.`in`.SanctionsMonitoringUseCase
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.application.port.out.MonitoringWorkRepository
import com.openbank.sanctions.application.port.out.PendingEntryChange
import com.openbank.sanctions.application.port.out.SanctionsMonitoringMetricsPort
import com.openbank.sanctions.domain.model.MatchKind
import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.MonitoringDetection
import com.openbank.sanctions.domain.model.MonitoringFinding
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsMatch
import com.openbank.sanctions.domain.model.SanctionsMonitoringDisabled
import com.openbank.sanctions.domain.model.ScreeningProfile
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/**
 * Ongoing monitoring of registered subjects (ADR-0376, refining ADR-0256 D3).
 *
 * Two paths raise findings, and both end in [raiseIfUncovered]:
 *
 *  - [runDelta] matches each added or changed list entry against the registry. It is the
 *    reverse of the live screen (one entry, every subject) and computes the same
 *    `word_similarity(subject name, entry search text)` against the same CUSTOMER floor.
 *  - [runSweep] re-evaluates whole subjects through [MonitoringScreening.uncoveredMatches], the
 *    function the live screen itself is built on. It is the safety net: it catches what a delta
 *    missed, and it is the only path that sees jurisdiction reference data, which no feed ingests.
 *
 * A finding is unique per (subject, list entry, version of the entry's names), so running either
 * path again, or both on the same change, raises nothing new.
 *
 * Nothing here logs a name. Log lines carry the registry id or the list entry reference only.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one use case: registration, the two scheduled paths and their shared finding rule
class SanctionsMonitoringService(
    private val subjects: MonitoredSubjectRepository,
    private val work: MonitoringWorkRepository,
    private val screening: MonitoringScreening,
    private val importer: SanctionsImportService,
    private val profiles: ScreeningProfileEvaluator,
    private val settings: SanctionsMonitoringSettings,
    private val metrics: SanctionsMonitoringMetricsPort,
    private val clock: Clock,
) : SanctionsMonitoringUseCase {

    override suspend fun register(
        ownerService: String,
        subjectRef: String,
        request: MonitoredSubjectRequest,
    ): MonitoredSubjectRegistration {
        // Refused, not accepted-and-ignored: a registration nothing monitors would read as cover.
        if (!settings.enabled) throw SanctionsMonitoringDisabled()
        require(OWNER_SERVICE.matches(ownerService)) { "ownerService must match ${OWNER_SERVICE.pattern}" }
        require(
            subjectRef.isNotBlank() &&
                subjectRef.length <= MAX_REF_LENGTH &&
                subjectRef.none {
                    it.isISOControl()
                },
        ) {
            "subjectRef must be 1 to $MAX_REF_LENGTH printable characters"
        }
        require(request.name.isNotBlank() && request.name.length <= MAX_NAME_LENGTH) {
            "name must be 1 to $MAX_NAME_LENGTH characters"
        }
        require(request.aliases.size <= MAX_ALIASES) { "aliases must hold at most $MAX_ALIASES names" }
        val aliases = request.aliases.mapIndexed { index, alias ->
            requireNotNull(alias) { "aliases[$index] must not be null" }
            require(alias.isNotBlank() && alias.length <= MAX_NAME_LENGTH) {
                "aliases[$index] must be 1 to $MAX_NAME_LENGTH characters"
            }
            alias
        }
        val searchNames = (listOf(request.name) + aliases).map(importer::normalizeForSearch)
            .filter { it.isNotEmpty() }.distinct()
        require(searchNames.isNotEmpty()) { "name must contain at least one searchable character" }
        val now = Instant.now(clock)
        val stored = subjects.upsert(
            MonitoredSubject(
                id = Ids.newId(),
                ownerService = ownerService,
                subjectRef = subjectRef,
                entityType = request.entityType,
                name = request.name,
                aliases = aliases,
                dateOfBirth = request.dateOfBirth?.takeIf { it.isNotBlank() },
                nationality = request.nationality?.takeIf { it.isNotBlank() },
                country = profiles.parseCountry(request.country),
                registeredAt = now,
                updatedAt = now,
                lastSweptAt = null,
            ),
            searchNames,
        )
        Log.infof("Monitored subject %s for %s", if (stored.created) "registered" else "updated", ownerService)
        return MonitoredSubjectRegistration(
            ownerService = stored.subject.ownerService,
            subjectRef = stored.subject.subjectRef,
            registeredAt = stored.subject.registeredAt,
            updatedAt = stored.subject.updatedAt,
            created = stored.created,
        )
    }

    /** Never gated on the flag: erasure must work in an environment that has switched monitoring off. */
    override suspend fun deregister(ownerService: String, subjectRef: String): Boolean {
        val deleted = subjects.delete(ownerService, subjectRef)
        if (deleted) Log.infof("Monitored subject deregistered for %s", ownerService)
        return deleted
    }

    /** Match the next bounded batch of changed list entries against the registry. */
    suspend fun runDelta(): MonitoringRunOutcome {
        if (!settings.enabled) return MonitoringRunOutcome.DISABLED
        if (work.pendingCount() > settings.deltaMaxPending) {
            Log.warn("Sanctions monitoring delta backlog exceeds its bound; requesting a full re-sweep instead")
            work.requestResweep()
            return MonitoringRunOutcome.RESWEEP_REQUESTED
        }
        for (change in work.claimPending(settings.claimLease, settings.deltaMaxEntriesPerRun)) {
            if (!matchChange(change)) {
                work.requestResweep()
                return MonitoringRunOutcome.RESWEEP_REQUESTED
            }
            work.completePending(change.id)
        }
        return MonitoringRunOutcome.COMPLETED
    }

    /** @return false when the entry matches more subjects than one run may raise, so the sweep must take over. */
    private suspend fun matchChange(change: PendingEntryChange): Boolean {
        val entry = work.findEntry(change.entryId)
        if (!change.active) {
            // The entry left its list. Close what was found about it, so that the same person
            // being listed again later is a new finding and not a duplicate of the old one.
            val ref = change.externalId?.let { "id:$it" } ?: entry?.let { "name:${it.primaryName}" }
            if (ref != null) work.markDelisted(change.listType, ref)
            return true
        }
        // Gone or inactive again since it was queued: the later change is queued too.
        if (entry == null || !entry.active || entry.listType !in ScreeningProfile.CUSTOMER.lists) return true
        val limit = settings.deltaMaxCandidatesPerEntry
        val candidates = subjects.findMatching(
            entry.searchText,
            profiles.nameSearchFloor(ScreeningProfile.CUSTOMER),
            limit + 1,
        )
        if (candidates.size > limit) {
            Log.warnf("List entry %s:%s matches more than %d monitored subjects", entry.listType, change.entryId, limit)
            return false
        }
        for (candidate in candidates) {
            val match = screening.customerMatch(
                entry.listType,
                entry.externalId,
                entry.primaryName,
                entry.programs,
                candidate.score,
            )
            raiseIfUncovered(candidate.subject, match, entry.namesVersion, MonitoringDetection.LIST_DELTA)
        }
        return true
    }

    /** Fully re-evaluate the next bounded batch of due subjects. */
    // One unreadable subject must not stop the batch; the run still reports FAILED.
    @Suppress("TooGenericExceptionCaught")
    suspend fun runSweep(): MonitoringRunOutcome {
        if (!settings.enabled) return MonitoringRunOutcome.DISABLED
        var failed = false
        val due = subjects.claimDueForSweep(settings.sweepInterval, settings.claimLease, settings.sweepBatchSize)
        for (subject in due) {
            try {
                sweep(subject)
                subjects.markSwept(subject.id)
            } catch (e: Exception) {
                // Not marked swept: the lease expires and the subject is retried.
                failed = true
                Log.errorf(e, "Sanctions monitoring sweep failed for registry row %s", subject.id)
            }
        }
        return if (failed) MonitoringRunOutcome.FAILED else MonitoringRunOutcome.COMPLETED
    }

    private suspend fun sweep(subject: MonitoredSubject) {
        val detection =
            if (subject.lastSweptAt == null) MonitoringDetection.BASELINE_SWEEP else MonitoringDetection.PERIODIC_SWEEP
        // uncoveredMatches has already applied the clearance rule to the whole match set.
        for (match in screening.uncoveredMatches(subject.toScreenCommand())) {
            // No version means the entry stopped being active between the two reads: nothing to raise.
            versionOf(match)?.let { raise(subject, match, it, detection) }
        }
    }

    private suspend fun versionOf(match: SanctionsMatch): String? = if (match.matchKind == MatchKind.JURISDICTION) {
        match.programs.firstOrNull()
    } else {
        work.namesVersion(match.listType, matchIdentity(match).second)
    }

    private suspend fun raiseIfUncovered(
        subject: MonitoredSubject,
        match: SanctionsMatch,
        version: String,
        detection: MonitoringDetection,
    ) {
        // PEP and jurisdiction matches are never covered by a clearance, exactly as in the live screen.
        val clearable = match.matchKind == MatchKind.SANCTIONS || match.matchKind == MatchKind.TERRORIST
        if (clearable && screening.isCleared(subject.toScreenCommand(), match)) return
        raise(subject, match, version, detection)
    }

    private suspend fun raise(
        subject: MonitoredSubject,
        match: SanctionsMatch,
        version: String,
        detection: MonitoringDetection,
    ) {
        // The live screen reports a match below the review floor as CLEAR; so does monitoring.
        val status = profiles.statusOf(listOf(match))
        if (status == SanctionsCheckStatus.CLEAR) return
        val finding = MonitoringFinding(
            id = Ids.newId(),
            subjectId = subject.id,
            ownerService = subject.ownerService,
            subjectRef = subject.subjectRef,
            entityType = subject.entityType,
            matchKind = requireNotNull(match.matchKind) { "a CUSTOMER-profile match always carries its kind" },
            listType = match.listType,
            listEntryRef = matchIdentity(match).second,
            listEntryVersion = version,
            matchScore = match.matchScore,
            matchStatus = status,
            detection = detection,
            detectedAt = Instant.now(clock),
        )
        if (work.raise(finding)) metrics.findingRaised(finding)
    }

    /** The CUSTOMER screen the owning service would run for this subject; `subjectId` binds clearances. */
    private fun MonitoredSubject.toScreenCommand() = ScreenEntityCommand(
        idempotencyKey = "monitoring:$id",
        entityType = entityType,
        name = name,
        aliases = aliases,
        dateOfBirth = dateOfBirth,
        nationality = nationality,
        subjectId = subjectRef,
        profile = ScreeningProfile.CUSTOMER.name,
        country = country,
    )

    companion object {
        private val OWNER_SERVICE = Regex("^[a-z][a-z0-9-]{1,62}$")
        private const val MAX_REF_LENGTH = 255
        private const val MAX_NAME_LENGTH = 500
        private const val MAX_ALIASES = 50
    }
}
