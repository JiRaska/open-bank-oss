// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.domain.model.SanctionsList
import com.openbank.sanctions.domain.model.SanctionsListChangeSet
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.UpdateSanctionsListRequest
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsListRepositoryImpl
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID

@ApplicationScoped
class SanctionsListService(
    private val repo: SanctionsListRepositoryImpl,
    private val importer: SanctionsImportService,
    private val outbox: SanctionsOutboxRepository,
    private val clock: Clock,
    /**
     * ADR-0256 D1 storm guard: the share of a list that may change in one refresh before the
     * refresh is treated as an upstream reformat rather than a regime action. Above it the
     * re-screening trigger is NOT raised — a schema change upstream must not become a fleet-wide
     * re-screening of the whole customer book. 0.5 by default: a real sanctions action edits a
     * handful of entries, never half the list.
     */
    private val stormThresholdShare: Double = DEFAULT_STORM_THRESHOLD_SHARE,
) {

    // CDI entry point: injects the production UTC clock. Tests use the primary constructor with a
    // fixed Clock for deterministic timestamps (ADR-0100 Layer 1).
    @Inject
    constructor(
        repo: SanctionsListRepositoryImpl,
        importer: SanctionsImportService,
        outbox: SanctionsOutboxRepository,
        @ConfigProperty(
            name = "openbank.sanctions.list-change.storm-threshold-share",
            defaultValue = "0.5",
        )
        stormThresholdShare: Double,
    ) : this(repo, importer, outbox, Clock.systemUTC(), stormThresholdShare)

    suspend fun listAll(): List<SanctionsList> = repo.listSanctionsLists()

    suspend fun getById(id: UUID): SanctionsList? = repo.findSanctionsListById(id)

    suspend fun update(id: UUID, req: UpdateSanctionsListRequest): SanctionsList? {
        val normalizedDays = normalizeCronDays(req.cronDays)
        validateCron(req.cronHour, req.cronMinute, normalizedDays)
        val normalizedSourceUrl = req.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        return repo.updateSanctionsList(
            id,
            req.enabled,
            normalizedSourceUrl,
            req.cronHour,
            req.cronMinute,
            normalizedDays,
        )
    }

    suspend fun refresh(listType: String): SanctionsList {
        Log.info("Manual refresh triggered for list: $listType")
        val list = repo.findByListType(listType) ?: throw NotFoundException("Sanctions list not found: $listType")
        val enumType = runCatching { SanctionsListType.valueOf(listType) }.getOrNull()
        val count = if (enumType != null) {
            val changeSet = importer.importList(enumType, list.sourceUrl)
            // Baseline = the entry count BEFORE this refresh. It is what the storm guard measures
            // the diff against, and it must be read before markUpdated() overwrites it.
            publishChangeEvent(list.id, changeSet, baselineEntryCount = list.lastEntryCount)
            // If importer found nothing (format stub / network error), fall back to stored count
            if (changeSet.changeCount > 0) {
                changeSet.changeCount
            } else {
                list.lastEntryCount ?: 0
            }
        } else {
            list.lastEntryCount ?: 0
        }
        return repo.markUpdated(listType, count)
            ?: throw IllegalStateException("Failed to persist sanctions list refresh for $listType")
    }

    /**
     * Publish a `SANCTIONS_LIST_CHANGED` outbox event when a refresh produced a content-level
     * diff (ADR-0256 D1). A content-identical refresh ([SanctionsListChangeSet.isEmpty]) raises
     * nothing — otherwise the daily cron would be a daily fleet-wide re-screening. The event
     * carries the changed/deactivated external_ids so the consumer (kyc-service) can re-screen
     * only the affected customers, not the whole book.
     *
     * Failure to persist the outbox row fails the refresh loudly: a list that *did* change but
     * emitted no event is precisely the screening gap this exists to close, so it must not pass
     * silently.
     */
    private suspend fun publishChangeEvent(listId: UUID, changeSet: SanctionsListChangeSet, baselineEntryCount: Int?) {
        if (changeSet.isEmpty) return

        // ADR-0256 D1 storm guard. An upstream schema reformat re-writes every row and is
        // indistinguishable, entry by entry, from "the whole list changed" — so the diff alone
        // would raise a trigger that re-screens the entire customer book. Above the threshold the
        // re-screening trigger is NOT raised; a distinct operator-facing event is, carrying counts
        // only.
        //
        // Skipped when there is no baseline (a list's FIRST import legitimately changes 100% of
        // nothing): with baseline null or 0 the share is undefined, and treating that as a storm
        // would mean a newly configured list could never raise its first trigger.
        if (baselineEntryCount != null && baselineEntryCount > 0) {
            val share = changeSet.changeCount.toDouble() / baselineEntryCount
            if (share > stormThresholdShare) {
                // Deliberately a DIFFERENT event type: a consumer must never be able to mistake it
                // for SANCTIONS_LIST_CHANGED and re-screen on it. Counts only, not the id list —
                // here the id list is "most of the list" and would be noise, not evidence.
                outbox.persistStandalone(
                    OutboxMessage(
                        aggregateId = listId,
                        eventType = EVENT_SANCTIONS_LIST_CHANGE_STORM,
                        payload = mapper.writeValueAsString(
                            stormPayload(changeSet, baselineEntryCount, share, stormThresholdShare),
                        ),
                    ),
                )
                // ERROR, not WARN: a withheld trigger means a real list change may go
                // un-re-screened until someone looks. Silence would trade one invisible failure
                // for another.
                Log.errorf(
                    "%s for %s: %d of %d entries changed (%.1f%% > %.1f%% threshold) — " +
                        "re-screening trigger WITHHELD, this looks like an upstream reformat. " +
                        "Verify the feed and re-screen deliberately if the change is real " +
                        "(ADR-0256 D1).",
                    EVENT_SANCTIONS_LIST_CHANGE_STORM,
                    changeSet.listType,
                    changeSet.changeCount,
                    baselineEntryCount,
                    share * PERCENT,
                    stormThresholdShare * PERCENT,
                )
                return
            }
        }
        val payload = mapper.writeValueAsString(
            mapOf(
                "listType" to changeSet.listType.name,
                "changedExternalIds" to changeSet.changedExternalIds.sorted(),
                "deactivatedExternalIds" to changeSet.deactivatedExternalIds.sorted(),
                "changeCount" to changeSet.changeCount,
            ),
        )
        outbox.persistStandalone(
            OutboxMessage(
                aggregateId = listId,
                eventType = EVENT_SANCTIONS_LIST_CHANGED,
                payload = payload,
            ),
        )
        Log.infof(
            "Published %s for %s (%d changed, %d deactivated)",
            EVENT_SANCTIONS_LIST_CHANGED,
            changeSet.listType,
            changeSet.changedExternalIds.size,
            changeSet.deactivatedExternalIds.size,
        )
    }

    suspend fun refreshAll(): List<SanctionsList> {
        Log.info("Refreshing all enabled sanctions lists")
        return repo.listSanctionsLists()
            .filter { list -> list.enabled }
            .map { list -> refresh(list.listType) }
    }

    /**
     * Scheduled refresh: checks cron schedule per list, calls the real importer.
     * Runs every 60s but only triggers a list when its cron time matches.
     *
     * Implemented as a plain `suspend fun` (Quarkus's scheduler has native Kotlin-coroutine
     * support — same pattern as [com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher]
     * subclasses fleet-wide). An earlier Uni-pipeline version could not call the suspend
     * [SanctionsImportService.importList] from inside a `transformToUniAndConcatenate` lambda and
     * was left as a stub that always fed `0` into the importer branch — so the scheduled path
     * never actually imported anything, even once real source URLs were registered. Each due
     * list is refreshed sequentially and a failure on one list is logged and does not stop the
     * others (a network hiccup on one feed must not block the rest of the fleet's screening data).
     */
    @Scheduled(every = "60s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Suppress("TooGenericExceptionCaught")
    // Deliberately broad: refresh() can throw the two exceptions it documents (NotFoundException,
    // IllegalStateException) but this scheduled loop must also survive whatever the reactive
    // Postgres/Vert.x layer throws on a transient DB hiccup for one list — the fleet-established
    // pattern for "one item's failure must not abort the batch" (see CopilotChatService,
    // SepaPaymentActivitiesImpl, VerificationCaseService, and others with the same suppress). A
    // failure here is logged and the list is simply retried on its next due tick.
    suspend fun scheduledRefresh() {
        val now = ZonedDateTime.now(clock).withSecond(0).withNano(0)
        val due = repo.listSanctionsLists().filter { it.isDueForScheduledRefresh(now) }
        for (list in due) {
            Log.infof("Scheduled refresh for %s", list.listType)
            try {
                refresh(list.listType)
            } catch (ex: Exception) {
                // observed-by: the list's own due-ness. A failed refresh does not advance
                // lastRefreshedAt, so `isDueForScheduledRefresh` stays true and the next tick
                // re-attempts it — the work is rescheduled rather than lost, which is why this
                // per-item catch is not the #5698 swallow even though it logs and continues.
                // Aborting the batch instead would let one unreachable list starve every other.
                Log.warnf(
                    "Scheduled refresh failed for %s (%s: %s) — will retry next due tick",
                    list.listType,
                    ex.javaClass.simpleName,
                    ex.message,
                )
            }
        }
    }

    private fun normalizeCronDays(raw: String?): String? {
        if (raw == null) return null
        val normalized = raw.split(',')
            .map { token -> token.trim().uppercase() }
            .filter { token -> token.isNotEmpty() }
            .distinct()

        require(normalized.isNotEmpty()) { "cronDays must contain at least one valid day" }
        require(normalized.all { it in ALLOWED_DAYS }) { "cronDays contains unsupported day value" }

        return normalized.joinToString(",")
    }

    private fun validateCron(hour: Int?, minute: Int?, days: String?) {
        require(hour == null || hour in 0..23) { "cronHour must be between 0 and 23" }
        require(minute == null || minute in 0..59) { "cronMinute must be between 0 and 59" }
        if (days != null) {
            require(days.split(',').all { it in ALLOWED_DAYS }) { "cronDays contains unsupported day value" }
        }
    }

    private fun SanctionsList.isDueForScheduledRefresh(now: ZonedDateTime): Boolean {
        if (!enabled) return false
        val currentDay = now.dayOfWeek.name.take(3)
        if (currentDay !in cronDays.split(',').filter { it.isNotBlank() }) return false
        if (now.hour != cronHour || now.minute != cronMinute) return false

        val lastRun = lastUpdatedAt ?: return true
        // Read the last run in the SAME zone as [now] — which is the injected clock's zone, UTC in
        // production. This comparison is a same-minute equality on (year, dayOfYear, hour, minute):
        // it is only meaningful if both sides are in one zone, and it used to read `now` from the
        // clock and `lastRunAt` from ZoneId.systemDefault(). Those agree only when the JVM default
        // happens to be UTC, so on any other host the de-duplication compared two different
        // clocks and was wrong in BOTH directions: a just-completed run read as not-yet-run (the
        // list re-imports on the next tick inside the due minute), and an unrelated run offset by
        // exactly the zone offset read as already-run (the due refresh is skipped). Not a zone
        // choice: taking the zone from `now` makes both sides one zone by construction.
        val lastRunAt = ZonedDateTime.ofInstant(lastRun, now.zone)
        return !(
            lastRunAt.year == now.year &&
                lastRunAt.dayOfYear == now.dayOfYear &&
                lastRunAt.hour == now.hour &&
                lastRunAt.minute == now.minute
            )
    }

    companion object {
        private val ALLOWED_DAYS = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        const val EVENT_SANCTIONS_LIST_CHANGED = "SANCTIONS_LIST_CHANGED"

        /** Raised INSTEAD of the re-screening trigger when the diff trips the D1 storm guard. */
        const val EVENT_SANCTIONS_LIST_CHANGE_STORM = "SANCTIONS_LIST_CHANGE_STORM"

        /** Default share of a list that may change in one refresh before it reads as a reformat. */
        const val DEFAULT_STORM_THRESHOLD_SHARE = 0.5

        private const val PERCENT = 100.0
        private val mapper = jacksonObjectMapper().findAndRegisterModules()
    }
}

/**
 * Payload for the ADR-0256 D1 storm event. Top-level and pure, placed AFTER the class: it keeps
 * SanctionsListService under detekt's TooManyFunctions threshold (which fires AT the limit, not
 * above it), and sitting after the class means it cannot take an annotation intended for a
 * following declaration.
 */
private fun stormPayload(
    changeSet: SanctionsListChangeSet,
    baselineEntryCount: Int,
    share: Double,
    thresholdShare: Double,
): Map<String, Any> = mapOf(
    "listType" to changeSet.listType.name,
    "changeCount" to changeSet.changeCount,
    "baselineEntryCount" to baselineEntryCount,
    "changedShare" to share,
    "thresholdShare" to thresholdShare,
)
