// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowConflict
import com.openbank.treasury.domain.cashflow.CashFlowOverride
import com.openbank.treasury.domain.cashflow.OverrideStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One generated schedule to store with the deal row (same transaction as the submission): the rows,
 * the approved overrides re-pointed at their new flows, and the overrides that became conflicts.
 */
data class ScheduleWrite(
    val dealId: UUID,
    val scheduleVersion: Int,
    val scheduleHash: String,
    val flows: List<CashFlow>,
    val carried: List<CashFlowOverride>,
    val conflicts: List<CashFlowConflict>,
    val conflicted: List<CashFlowOverride>,
)

/** A deal version as stored: terms are the JSON the repository serialised at that version. */
data class DealVersionView(
    val dealId: UUID,
    val version: Int,
    val scheduleVersion: Int?,
    val scheduleHash: String?,
    val createdBy: String,
    val createdAt: Instant,
    val changes: List<String>,
)

/** A flow with the deal facts the event calendar needs. */
data class CalendarFlow(val flow: CashFlow, val bookId: String?, val maturityDate: LocalDate, val product: String)

/** A live flow for the cash-flow analysis (contract 8.4): its deal's kind, book and cash account. */
data class AnalysisFlow(
    val flow: CashFlow,
    val kind: com.openbank.treasury.domain.model.DealKind,
    val bookId: String?,
    val cashAccountId: String?,
)

/** More flows fall in the requested horizon than one analysis may read: narrow it (422). */
class AnalysisTooLargeException(message: String) : RuntimeException(message)

/** A guarded flow write found the row changed underneath it (a concurrent fixing, payment or override): 409. */
class CashFlowConflictException(message: String) : IllegalStateException(message)

interface CashFlowRepository {
    /** The rows of [scheduleVersion], or of the latest version when null. */
    suspend fun flows(dealId: UUID, scheduleVersion: Int?): List<CashFlow>

    suspend fun overrides(dealId: UUID): List<CashFlowOverride>

    /** Overrides in [status] across every deal, oldest first, at most [limit]. */
    suspend fun overridesByStatus(status: OverrideStatus, limit: Int): List<CashFlowOverride>

    suspend fun findOverride(overrideId: UUID): CashFlowOverride?

    suspend fun conflicts(dealId: UUID): List<CashFlowConflict>

    suspend fun versions(dealId: UUID): List<DealVersionView>

    /**
     * Store an override decision with the flow it changes (if any), the outbox event and the
     * command key in ONE transaction; the override row must still hold [priorStatus] (locked), so
     * a concurrent decision is refused rather than overwritten.
     */
    suspend fun saveOverride(
        override: CashFlowOverride,
        priorStatus: OverrideStatus?,
        flow: CashFlow?,
        event: DealEvent?,
        command: CommandKey?,
    )

    /** Latest-schedule flows of ACTUAL and PLANNED deals with a payment or fixing date in [from, to]. */
    suspend fun calendarFlows(from: LocalDate, to: LocalDate, bookId: String?): List<CalendarFlow>

    /**
     * The current-schedule flows of live deals of [kinds] paying in [from]..[to]; throws
     * [AnalysisTooLargeException] past [maxRows] rather than answering with part of the book.
     */
    suspend fun analysisFlows(
        from: LocalDate,
        to: LocalDate,
        kinds: Set<com.openbank.treasury.domain.model.DealKind>,
        maxRows: Int,
    ): List<AnalysisFlow> = throw UnsupportedOperationException("cash-flow analysis is not wired")
}
