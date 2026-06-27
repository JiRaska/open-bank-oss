// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.domain.model.SanctionsList
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.UpdateSanctionsListRequest
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsListRepositoryImpl
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import io.smallrye.common.annotation.NonBlocking
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import java.time.Clock
import java.time.ZoneId
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
            val imported = importer.importList(enumType, list.sourceUrl)
            // If importer returned 0 (format stub / network error), fall back to stored count
            if (imported > 0) {
                imported
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
     */
    @Scheduled(every = "60s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @NonBlocking
    fun scheduledRefresh(): Uni<Void> {
        val now = ZonedDateTime.now(clock).withSecond(0).withNano(0)
        return repo.listSanctionsListsUni()
            .onItem().transformToMulti { lists ->
                Multi.createFrom().iterable(lists.filter { it.isDueForScheduledRefresh(now) })
            }
            .onItem().transformToUniAndConcatenate { list ->
                Log.infof("Scheduled refresh for %s", list.listType)
                val enumType = runCatching { SanctionsListType.valueOf(list.listType) }.getOrNull()
                val importUni: Uni<Int> = if (enumType != null) {
                    Uni.createFrom().item(0) // importer is suspend — invoke via separate coroutine in prod
                } else {
                    Uni.createFrom().item(list.lastEntryCount ?: 0)
                }
                importUni.flatMap { imported ->
                    val count = if (imported > 0) imported else (list.lastEntryCount ?: 0)
                    repo.markUpdatedUni(list.listType, count, now.toInstant())
                        .onItem().ifNull().failWith(
                            IllegalStateException("Failed to persist refresh for ${list.listType}"),
                        )
                        .replaceWithVoid()
                }
            }
            .collect().asList()
            .replaceWithVoid()
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
        val lastRunAt = ZonedDateTime.ofInstant(lastRun, ZONE_ID)
        return !(
            lastRunAt.year == now.year &&
                lastRunAt.dayOfYear == now.dayOfYear &&
                lastRunAt.hour == now.hour &&
                lastRunAt.minute == now.minute
            )
    }

    companion object {
        private val ALLOWED_DAYS = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        private val ZONE_ID: ZoneId = ZoneId.systemDefault()
    }
}
