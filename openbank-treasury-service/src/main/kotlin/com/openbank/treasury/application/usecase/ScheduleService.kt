// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.calendar.DateRoll
import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.cashflow.FixingSource
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.port.out.ScheduleWrite
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.ProductLedger
import com.openbank.treasury.domain.cashflow.ScheduleAdapter
import com.openbank.treasury.domain.cashflow.ScheduleContext
import com.openbank.treasury.domain.cashflow.ScheduleRegeneration
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.JointCalendar
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.LegSpec.Companion.sha256
import com.openbank.treasury.domain.model.OverlayCalendar
import com.openbank.treasury.domain.model.RateType
import com.openbank.treasury.domain.reference.BaseCalendar
import com.openbank.treasury.domain.reference.CalendarPayload
import com.openbank.treasury.domain.reference.IndexPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceNotEffectiveException
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceRef
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A generated (not yet stored) schedule and what it would store. */
data class GeneratedSchedule(val flows: List<CashFlow>, val hash: String, val write: ScheduleWrite)

/**
 * ADR-0344 D3/D4: validates that everything a deal names is effective, resolves calendars and
 * index conventions from the reference store, generates the ledger through the shared engine, and
 * carries approved overrides across regeneration. Reference data is read as effective on the
 * trade date when the deal froze its conventions (`fixedConvention`), else as effective today.
 */
class ScheduleService(
    private val references: ReferenceRepository,
    private val cashFlows: CashFlowRepository,
    private val clock: Clock,
    private val newId: () -> UUID = { UUID.randomUUID() },
) {
    /** Every reference record [deal] names must be ACTIVE on its trade date (else 422 with the list). */
    suspend fun requireEffective(deal: Deal) {
        val refs = referencesOf(deal)
        val missing = references.notEffective(refs, deal.tradeDate)
        if (missing.isNotEmpty()) throw ReferenceNotEffectiveException(missing, deal.tradeDate)
    }

    fun referencesOf(deal: Deal): List<ReferenceRef> = buildList {
        add(ReferenceRef(ReferenceKind.COUNTERPARTY, deal.counterpartyId))
        deal.bookId?.let { add(ReferenceRef(ReferenceKind.BOOK, it)) }
        deal.header.calendars.forEach { add(ReferenceRef(ReferenceKind.CALENDAR, it)) }
        deal.legs.forEach { leg ->
            leg.paymentCalendars.forEach { add(ReferenceRef(ReferenceKind.CALENDAR, it)) }
            leg.rate.index?.let { add(ReferenceRef(ReferenceKind.INDEX, it)) }
        }
        deal.header.securityTerms?.let { add(ReferenceRef(ReferenceKind.SECURITY, it.securityIsin)) }
        deal.header.collateral?.let { add(ReferenceRef(ReferenceKind.SECURITY, it.securityIsin)) }
        deal.header.securityTerms?.settlementPlace?.let { add(ReferenceRef(ReferenceKind.SETTLEMENT_PLACE, it)) }
    }.distinct()

    /** Generate the next schedule version for [deal], carrying its approved overrides. Stores nothing. */
    suspend fun generate(deal: Deal, fixings: FixingSource = FixingSource.NONE): GeneratedSchedule {
        val asOf = if (deal.header.fixedConvention) {
            deal.tradeDate
        } else {
            maxOf(
                deal.tradeDate,
                AccountingClock.bank(clock).today(),
            )
        }
        val used = mutableListOf<ReferenceRecord>()
        val calendarRecords = mutableMapOf<String, FinanceCalendar>()
        suspend fun calendar(code: String): FinanceCalendar = calendarRecords[code] ?: run {
            val record = effective(ReferenceKind.CALENDAR, code, asOf).also { used += it }
            val p = record.payload as CalendarPayload
            OverlayCalendar(Calendars.of(base(p.baseCalendar)), p.extraHolidays.toSet(), p.removedHolidays.toSet())
                .also { calendarRecords[code] = it }
        }
        val dealCalendars = deal.header.calendars.map { calendar(it) }
        val legCalendars = deal.legs.map { leg ->
            when {
                leg.paymentCalendars.isNotEmpty() -> JointCalendar(leg.paymentCalendars.map { calendar(it) })
                dealCalendars.isNotEmpty() -> JointCalendar(dealCalendars)
                else -> Calendars.forCurrencies(leg.currency)
            }
        }
        val indices = deal.legs.mapNotNull { it.rate.index }.distinct().associateWith { code ->
            (effective(ReferenceKind.INDEX, code, asOf).also { used += it }.payload as IndexPayload)
        }
        val fixingCalendars = indices.mapValues { (_, p) -> calendar(p.fixingCalendar) }
        val previous = deal.scheduleVersion
        val version = previous + 1
        val ctx = ScheduleContext(
            dealId = deal.id,
            scheduleVersion = version,
            calendarFor = { leg -> legCalendars[deal.legs.indexOf(leg)] },
            fixingCalendar = { fixingCalendars[it] },
            fixingLag = { indices[it]?.fixingLagBusinessDays },
            roll = roll(deal),
            rollDirection = deal.header.rollDirection,
            newId = newId,
            fixings = fixings,
        )
        val generated = ProductLedger.shape(deal, ScheduleAdapter.generate(deal.legs, ctx), ctx)
        val approved = if (previous == 0) emptyList() else cashFlows.overrides(deal.id)
        val regen = ScheduleRegeneration.regenerate(generated, approved, previous, newId, clock.instant())
        val conflicted = approved.filter { o -> regen.conflicts.any { it.overrideId == o.overrideId } }
            .map { it.copy(status = com.openbank.treasury.domain.cashflow.OverrideStatus.CONFLICT) }
        val hash = hash(deal.legs, used)
        return GeneratedSchedule(
            regen.flows,
            hash,
            ScheduleWrite(deal.id, version, hash, regen.flows, regen.carried, regen.conflicts, conflicted),
        )
    }

    /**
     * ADR-0344 D5: an index's conventions and its fixing calendar as effective on [on] — the quality
     * thresholds the fixing gate applies, and the calendar its staleness is counted on.
     */
    suspend fun indexTerms(code: String, on: LocalDate): Pair<IndexPayload, FinanceCalendar> {
        val index = effective(ReferenceKind.INDEX, code, on).payload as IndexPayload
        val calendar = effective(ReferenceKind.CALENDAR, index.fixingCalendar, on).payload as CalendarPayload
        return index to OverlayCalendar(
            Calendars.of(base(calendar.baseCalendar)),
            calendar.extraHolidays.toSet(),
            calendar.removedHolidays.toSet(),
        )
    }

    private suspend fun effective(kind: ReferenceKind, key: String, on: LocalDate): ReferenceRecord =
        references.versions(kind, key).lastOrNull { it.isEffectiveOn(on) }
            ?: throw ReferenceNotEffectiveException(listOf(ReferenceRef(kind, key)), on)

    private fun base(b: BaseCalendar): BusinessCalendar = when (b) {
        BaseCalendar.CERTIS -> BusinessCalendar.certis()
        BaseCalendar.TARGET2 -> BusinessCalendar.target2()
        BaseCalendar.WEEKEND_ONLY -> BusinessCalendar.weekendOnly()
    }

    /** The legacy products never rolled a captured date; the others default to modified following. */
    private fun roll(deal: Deal): DateRoll = deal.header.businessDayConvention?.let(DateRoll::valueOf)
        ?: if (deal.product.legacy) DateRoll.UNADJUSTED else DateRoll.MODIFIED_FOLLOWING

    /** The legs and the exact reference versions used: a change to either is a different schedule. */
    private fun hash(legs: List<LegSpec>, used: List<ReferenceRecord>): String = sha256(
        "v1|" + legs.joinToString("|") { it.specHash() } + "|refs=" +
            used.map { "${it.kind.wire}:${it.key}@${it.version}" }.distinct().sorted().joinToString(","),
    )

    companion object {
        fun projectedCount(flows: List<CashFlow>) = flows.count { it.amount == null }

        fun floating(leg: LegSpec) = leg.rate.type == RateType.FLOATING
    }
}
