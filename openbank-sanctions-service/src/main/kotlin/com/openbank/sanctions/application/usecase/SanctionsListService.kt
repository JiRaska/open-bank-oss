// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.ListImportOutcome
import com.openbank.sanctions.domain.model.SanctionsList
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.UpdateSanctionsListRequest
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsListRepositoryImpl
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID

@ApplicationScoped
class SanctionsListService(
    private val repo: SanctionsListRepositoryImpl,
    private val importer: SanctionsImportService,
    private val clock: Clock,
) {

    // CDI entry point: injects the production UTC clock. Tests use the primary constructor with a
    // fixed Clock for deterministic timestamps (ADR-0100 Layer 1).
    @Inject
    constructor(
        repo: SanctionsListRepositoryImpl,
        importer: SanctionsImportService,
    ) : this(repo, importer, Clock.systemUTC())

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
            val result = importer.importList(enumType, list.sourceUrl)
            // Key on the outcome, never on "count > 0" (issue #8362 / #4348): only IMPORTED means
            // the stored list now reflects the upstream source; every other outcome left the
            // stored entries untouched, so the stored count stays the honest number.
            if (result.outcome == ListImportOutcome.IMPORTED) {
                result.entriesImported
            } else {
                list.lastEntryCount ?: 0
            }
        } else {
            list.lastEntryCount ?: 0
        }
        return repo.markUpdated(listType, count)
            ?: throw IllegalStateException("Failed to persist sanctions list refresh for $listType")
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
    }
}
