// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.tax.application.port.out.ReturnCatalogueSource
import com.openbank.tax.application.port.out.ReturnDataPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.port.out.StatutoryReturnRepository
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxValidationException
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.domain.returns.ReturnScope
import com.openbank.tax.domain.returns.ReturnStatus
import com.openbank.tax.domain.returns.StatutoryReturn
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Which entities report: the company itself, plus the funds it manages. */
data class ReportingEntities(val companyId: String, val fundIds: List<String>, val reportingStart: LocalDate?) {
    fun forScope(scope: ReturnScope): List<String> = when (scope) {
        ReturnScope.COMPANY -> listOf(companyId)
        ReturnScope.FUND -> fundIds
    }
}

enum class BreachKind { NOT_ASSEMBLED, NOT_SUBMITTED }

data class ReturnBreach(
    val catalogueId: String,
    val returnCode: String,
    val entityId: String,
    val period: String,
    val dueDate: LocalDate,
    val kind: BreachKind,
)

class StatutoryReturnNotFoundException(message: String) : RuntimeException(message)

/**
 * Pension (and any catalogue-defined) statutory returns on the ADR-0180 filing lifecycle (ADR-0336).
 *
 * The service deliberately does not decide WHAT a return contains — the catalogue does — only
 * that a return is assembled from sourced figures after its period ends, approved by a second
 * person, submitted exactly as attested, and visible the moment its deadline passes.
 */
@ApplicationScoped
class StatutoryReturnService(
    private val catalogues: ReturnCatalogueSource,
    private val data: ReturnDataPort,
    private val repository: StatutoryReturnRepository,
    private val entities: ReportingEntities,
    private val accountingClock: AccountingClock,
    private val clock: Clock,
) {
    fun catalogues(): List<ReturnCatalogue> = catalogues.catalogues()

    suspend fun assemble(
        catalogueId: String,
        returnCode: String,
        entityId: String,
        periodLabel: String,
        by: String,
    ): StatutoryReturn {
        val (catalogue, definition) = resolve(catalogueId, returnCode)
        val period = ReportingPeriod.parse(definition.periodicity, periodLabel)
        requireDeclaredEntity(entities, definition, entityId)
        requirePeriodEnded(definition, period, accountingClock.today())
        val latest = repository.latestRevision(catalogue.id, definition.code, entityId, period)
        requireNoOpenRevision(latest, definition, period, entityId)
        val values = data.fetch(catalogue, definition, entityId, period)
        val assembled = StatutoryReturn.assemble(
            catalogue = catalogue,
            definition = definition,
            entityId = entityId,
            period = period,
            revision = (latest?.revision ?: 0) + 1,
            values = values,
            by = by,
            at = Instant.now(clock),
        )
        return repository.insert(assembled)
    }

    suspend fun approve(id: UUID, by: String): StatutoryReturn {
        val current = get(id)
        return repository.save(current.approve(by, Instant.now(clock)), expectedVersion = current.version)
    }

    suspend fun submit(id: UUID, reference: String, by: String): StatutoryReturn {
        val current = get(id)
        return repository.save(current.submit(reference, by, Instant.now(clock)), expectedVersion = current.version)
    }

    suspend fun get(id: UUID): StatutoryReturn =
        repository.findReturn(id) ?: throw StatutoryReturnNotFoundException("No statutory return $id")

    suspend fun list(): List<StatutoryReturn> = repository.listReturns()

    /**
     * Every return whose statutory deadline has passed without a SUBMITTED revision.
     *
     * Counts returns that were never assembled, not only stored ones left unsubmitted: the silent
     * failure in periodic reporting is the return nobody started, and a register of stored rows
     * cannot see it. A missing reporting start or fund roster makes the expected obligations
     * unknowable, so the check fails instead of publishing a misleading zero.
     */
    suspend fun breaches(): List<ReturnBreach> {
        val start = entities.reportingStart ?: throw ReturnDataUnavailableException(
            "Statutory return reporting start is not configured; deadline status is unavailable",
        )
        if (catalogues.catalogues().any { catalogue -> catalogue.returns.any { it.scope == ReturnScope.FUND } } &&
            entities.fundIds.isEmpty()
        ) {
            throw ReturnDataUnavailableException(
                "Statutory return fund roster is not configured; deadline status is unavailable",
            )
        }
        val today = accountingClock.today()
        val stored = repository.listReturns()
        val latestByKey = stored.groupBy { Key(it.catalogueId, it.returnCode, it.entityId, it.period) }
            .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
        val breaches = mutableListOf<ReturnBreach>()
        latestByKey.forEach { (key, latest) ->
            if (latest.status != ReturnStatus.SUBMITTED && latest.isOverdueAt(today)) {
                breaches +=
                    ReturnBreach(
                        key.catalogueId,
                        key.returnCode,
                        key.entityId,
                        key.period.label,
                        latest.dueDate,
                        BreachKind.NOT_SUBMITTED,
                    )
            }
        }
        return breaches + notAssembled(start, today, latestByKey.keys)
    }

    private fun notAssembled(start: LocalDate, today: LocalDate, existing: Set<Key>): List<ReturnBreach> =
        catalogues.catalogues().flatMap { catalogue ->
            catalogue.returns.flatMap { definition ->
                expectedPeriods(definition, start, today).flatMap { period ->
                    entities.forScope(definition.scope)
                        .filter { entityId -> Key(catalogue.id, definition.code, entityId, period) !in existing }
                        .map { entityId ->
                            ReturnBreach(
                                catalogue.id,
                                definition.code,
                                entityId,
                                period.label,
                                definition.dueDate(period),
                                BreachKind.NOT_ASSEMBLED,
                            )
                        }
                }
            }
        }

    /** Periods ending on/after [start] whose due date is already behind [today]. */
    private fun expectedPeriods(
        definition: ReturnDefinition,
        start: LocalDate,
        today: LocalDate,
    ): List<ReportingPeriod> {
        val periods = mutableListOf<ReportingPeriod>()
        var period = ReportingPeriod.lastEndedBefore(definition.periodicity, today)
        while (!period.endDate.isBefore(start)) {
            if (today.isAfter(definition.dueDate(period))) periods += period
            period = period.previous()
        }
        return periods
    }

    private fun resolve(catalogueId: String, returnCode: String): Pair<ReturnCatalogue, ReturnDefinition> {
        val catalogue = catalogues.catalogues().firstOrNull { it.id == catalogueId }
            ?: throw StatutoryReturnNotFoundException("No return catalogue '$catalogueId'")
        val definition = catalogue.definition(returnCode)
            ?: throw StatutoryReturnNotFoundException("Catalogue '$catalogueId' has no return '$returnCode'")
        return catalogue to definition
    }

    private data class Key(
        val catalogueId: String,
        val returnCode: String,
        val entityId: String,
        val period: ReportingPeriod,
    )
}

private fun requireDeclaredEntity(entities: ReportingEntities, definition: ReturnDefinition, entityId: String) {
    if (entityId !in entities.forScope(definition.scope)) {
        throw TaxValidationException(
            "$entityId is not a declared ${definition.scope} reporting entity for ${definition.code}",
        )
    }
}

private fun requirePeriodEnded(definition: ReturnDefinition, period: ReportingPeriod, today: LocalDate) {
    if (!period.endDate.isBefore(today)) {
        throw TaxConflictException(
            "${definition.code} ${period.label} has not ended (accounting day is $today) — cannot assemble a partial period",
        )
    }
}

/** Figures are never re-totalled underneath an approval; a correction follows a SUBMITTED revision. */
private fun requireNoOpenRevision(
    latest: StatutoryReturn?,
    definition: ReturnDefinition,
    period: ReportingPeriod,
    entityId: String,
) {
    if (latest != null && latest.status != ReturnStatus.SUBMITTED) {
        throw TaxConflictException(
            "${definition.code} ${period.label} for $entityId already has revision ${latest.revision} " +
                "in ${latest.status} — finish or correct that one",
        )
    }
}
