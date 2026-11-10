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
import com.openbank.tax.domain.returns.ReturnStatus
import com.openbank.tax.domain.returns.ReturnValidationException
import com.openbank.tax.domain.returns.StatutoryReturn
import com.openbank.tax.domain.returns.ValidationFinding
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Why a CEÚ overview is held rather than assembled. Each is a distinct, logged scheduler outcome. */
enum class CeuHoldReason { WAITING_FOR_ACKNOWLEDGEMENT, INITIAL_LOAD_NOT_ATTESTED }

/**
 * A CEÚ overview cannot be assembled YET — not a data fault. An [IllegalStateException], so a
 * manual `POST /assemble` answers 422 with the reason.
 */
class CeuFilingHeldException(val reason: CeuHoldReason, message: String) : IllegalStateException(message)

/**
 * Assembles one CEÚ working-day change overview (ADR-0367 D7, #15292).
 *
 * The baseline is what ČNB has ACCEPTED: the latest ACKNOWLEDGED overview for an earlier window,
 * re-derived from the projection as known at the instant that overview read it
 * (`register_known_at_epoch_ms`, part of its attested content, millisecond precision — the
 * current snapshot is cut at the same truncated instant, so re-derivation is exact). So:
 *
 *  - while the latest filed overview is SUBMITTED with no ČNB answer, nothing is assembled
 *    ([CeuHoldReason.WAITING_FOR_ACKNOWLEDGEMENT]): its answer decides the baseline;
 *  - no acknowledged predecessor ⇒ the overview diffs against an empty register — the initial
 *    full load, refused unless `openbank.ceu.initial-load-attested` is true
 *    ([CeuHoldReason.INITIAL_LOAD_NOT_ATTESTED]): the projection only holds what the consumers have
 *    seen, so someone must attest it is the complete register before it is filed as one;
 *  - a predecessor that was rejected or never filed is not a baseline, so its changes roll into
 *    this overview instead of being lost; a REJECTED window may itself be re-assembled as a new
 *    revision, diffed against the same acknowledged baseline;
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
    @ConfigProperty(name = "openbank.ceu.initial-load-attested", defaultValue = "false")
    private val initialLoadAttested: Boolean,
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
        // Truncated so the stored KNOWN_AT and this snapshot's cut are the same instant: a fact
        // recorded within the dropped sub-millisecond would otherwise be in neither or both.
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
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
        val order = compareBy<StatutoryReturn>({ it.period.endDate }, { it.revision })
        val ours = returns.listReturns().filter {
            it.catalogueId == catalogue.id && it.returnCode == definition.code && it.entityId == entityId
        }
        // Any window up to and including this one: a SUBMITTED correction of this very window
        // is as unanswered as yesterday's.
        requireAnswered(
            ours.filter { !it.period.endDate.isAfter(period.endDate) && it.status.filed }.maxWithOrNull(order),
            definition,
            period,
        )
        val filed = ours.filter { it.period.endDate.isBefore(period.endDate) && it.status == ReturnStatus.ACKNOWLEDGED }
            .maxWithOrNull(order)
            ?: return initialLoad(definition, period)
        val knownAt = filed.datapoints[KNOWN_AT]?.let { Instant.ofEpochMilli(it.toLong()) }
            ?: throw ReturnDataUnavailableException(
                "${definition.code} ${filed.period.label} r${filed.revision} carries no $KNOWN_AT; its baseline cannot be re-derived",
            )
        return snapshot(filed.period, knownAt)
    }

    private fun requireAnswered(latestFiled: StatutoryReturn?, definition: ReturnDefinition, period: ReportingPeriod) {
        if (latestFiled?.status == ReturnStatus.SUBMITTED) {
            throw CeuFilingHeldException(
                CeuHoldReason.WAITING_FOR_ACKNOWLEDGEMENT,
                "${definition.code} ${latestFiled.period.label} r${latestFiled.revision} is SUBMITTED without a " +
                    "ČNB answer; record its acknowledgement or rejection before assembling ${period.label}",
            )
        }
    }

    private fun initialLoad(definition: ReturnDefinition, period: ReportingPeriod): RegisterSnapshot {
        if (!initialLoadAttested) {
            throw CeuFilingHeldException(
                CeuHoldReason.INITIAL_LOAD_NOT_ATTESTED,
                "${definition.code} ${period.label} would be the initial full load; set " +
                    "openbank.ceu.initial-load-attested=true once the register projection is attested complete",
            )
        }
        return RegisterSnapshot.empty(period.endDate, Instant.EPOCH)
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
