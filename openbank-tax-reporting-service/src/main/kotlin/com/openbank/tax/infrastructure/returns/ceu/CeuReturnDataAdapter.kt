// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.ceu

import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.application.port.out.ReturnAssemblyData
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.port.out.StatutoryReturnRepository
import com.openbank.tax.domain.ceu.CeuRole
import com.openbank.tax.domain.ceu.ChangeKind
import com.openbank.tax.domain.ceu.ChangeOverview
import com.openbank.tax.domain.ceu.RegisterChange
import com.openbank.tax.domain.ceu.RegisterSnapshot
import com.openbank.tax.domain.ceu.ReportingWindow
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.domain.returns.ReturnLine
import com.openbank.tax.domain.returns.ReturnValidationException
import com.openbank.tax.domain.returns.StatutoryReturn
import com.openbank.tax.domain.returns.ValidationFinding
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/**
 * Assembles one CEÚ working-day change overview (ADR-0367 D7, #15292).
 *
 * The baseline is what ČNB already HOLDS: the latest filed (SUBMITTED or ACKNOWLEDGED) overview
 * for an earlier window, re-derived from the projection as known at the instant that overview
 * read it (`register_known_at_epoch_ms`, part of its attested content). So:
 *
 *  - no filed predecessor ⇒ the overview diffs against an empty register — the initial full load;
 *  - a predecessor that was rejected or never filed is not a baseline, so its changes roll into
 *    this overview instead of being lost;
 *  - a late fact for an already-reported day is invisible to the baseline and visible now, so it
 *    is reported as a change here.
 *
 * Synthetic data is excluded inside [RegisterSnapshot.fold] and its count recorded as datapoints.
 * The CEÚ-specific checks below run before anything becomes a stored return, so nothing invalid
 * reaches the four-eyes approval.
 */
@ApplicationScoped
class CeuReturnDataAdapter(
    private val register: CeuRegisterRepository,
    private val returns: StatutoryReturnRepository,
    private val clock: Clock,
) {
    suspend fun fetch(
        catalogue: ReturnCatalogue,
        definition: ReturnDefinition,
        entityId: String,
        period: ReportingPeriod,
    ): ReturnAssemblyData {
        if (period.periodicity != Periodicity.WORKING_DAY) {
            throw ReturnDataUnavailableException("${definition.code} is a working-day feed, not ${period.periodicity}")
        }
        val reportingDay = period.endDate.plusDays(1)
        val window = ReportingWindow.forReportingDay(
            reportingDay,
            ReportingPeriod.WORKING_DAYS.previousBusinessDay(reportingDay),
        )
        val now = Instant.now(clock)
        val baseline = baselineFor(catalogue, definition, entityId, period)
        val current = snapshot(period, now)
        val overview = ChangeOverview.diff(window, baseline, current)

        val findings = validate(overview)
        if (findings.isNotEmpty()) throw ReturnValidationException(findings)

        return ReturnAssemblyData(values = datapoints(overview, now), lines = overview.changes.map(::line))
    }

    private suspend fun baselineFor(
        catalogue: ReturnCatalogue,
        definition: ReturnDefinition,
        entityId: String,
        period: ReportingPeriod,
    ): RegisterSnapshot {
        val filed = returns.listReturns()
            .filter {
                it.catalogueId == catalogue.id &&
                    it.returnCode == definition.code &&
                    it.entityId == entityId &&
                    it.period.endDate.isBefore(period.endDate) &&
                    it.status.filed
            }
            .maxWithOrNull(compareBy<StatutoryReturn>({ it.period.endDate }, { it.revision }))
            ?: return RegisterSnapshot.empty(period.endDate, Instant.EPOCH)
        val knownAt = filed.datapoints[KNOWN_AT]?.let { Instant.ofEpochMilli(it.toLong()) }
            ?: throw ReturnDataUnavailableException(
                "${definition.code} ${filed.period.label} r${filed.revision} carries no $KNOWN_AT; its baseline cannot be re-derived",
            )
        return snapshot(filed.period, knownAt)
    }

    private suspend fun snapshot(period: ReportingPeriod, knownAt: Instant): RegisterSnapshot = RegisterSnapshot.fold(
        register.factsUpTo(period.endDate, knownAt),
        period.endDate,
        knownAt,
        register.syntheticParties(knownAt),
    )

    /** CEÚ record-level checks (`validation_rule_set: ceu-daily-v1`). */
    private fun validate(overview: ChangeOverview): List<ValidationFinding> = overview.changes.flatMap { change ->
        val account = change.account
        buildList {
            if (account.accountNumber.isBlank()) {
                add(ValidationFinding("CEU-ACCOUNT-NUMBER", "account ${account.accountId} has no account number"))
            }
            if (change.kind != ChangeKind.CLOSED && account.persons.none { it.role == CeuRole.HOLDER }) {
                add(ValidationFinding("CEU-HOLDER", "open account ${account.accountNumber} has no holder"))
            }
            if (account.closedOn != null && account.closedOn.isBefore(account.openedOn)) {
                add(ValidationFinding("CEU-DATES", "account ${account.accountNumber} closes before it opens"))
            }
        }
    }

    private fun datapoints(overview: ChangeOverview, knownAt: Instant): Map<String, BigDecimal> {
        fun count(kind: ChangeKind) = overview.changes.count { it.kind == kind }.toBigDecimal()
        return mapOf(
            "changes_total" to overview.changes.size.toBigDecimal(),
            "changes_new" to count(ChangeKind.NEW),
            "changes_changed" to count(ChangeKind.CHANGED),
            "changes_closed" to count(ChangeKind.CLOSED),
            "synthetic_accounts_dropped" to overview.droppedSyntheticAccounts.toBigDecimal(),
            "synthetic_relations_dropped" to overview.droppedSyntheticRelations.toBigDecimal(),
            "initial_load" to (if (overview.initialLoad) BigDecimal.ONE else BigDecimal.ZERO),
            KNOWN_AT to knownAt.toEpochMilli().toBigDecimal(),
        )
    }

    private fun line(change: RegisterChange) = ReturnLine(
        mapOf(
            "change" to change.kind.name,
            "account_id" to change.account.accountId.toString(),
            "account_number" to change.account.accountNumber,
            "currency" to change.account.currency,
            "opened_on" to change.account.openedOn.toString(),
            "closed_on" to change.account.closedOn?.toString(),
            "persons" to change.account.persons
                .map { "${it.role}:${it.partyId}" }
                .sorted()
                .joinToString(";"),
        ),
    )

    companion object {
        const val CATALOGUE_ID = "cz-ceu"
        const val RETURN_CODE = "CEUSIFE10"
        const val KNOWN_AT = "register_known_at_epoch_ms"
    }
}
