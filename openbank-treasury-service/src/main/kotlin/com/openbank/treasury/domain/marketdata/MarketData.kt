// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.marketdata

import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Where an index observation came from (ADR-0344 D5). FEED is reserved for a future market-data feed adapter. */
enum class ObservationSource { IMPORT, MANUAL, FEED }

enum class ObservationStatus { PENDING, ACTIVE, REJECTED }

/**
 * One published value of a rate index on one date, in PERCENT (3.75 = 3.75 %). A value is usable for
 * a fixing only when ACTIVE, which takes a four-eyes decision on its batch. [sha256] is the
 * provenance hash of (index, date, value, source) recorded on every flow fixed from it.
 */
data class IndexObservation(
    val index: String,
    val date: LocalDate,
    val value: BigDecimal,
    val source: ObservationSource,
    val batchId: UUID,
    val status: ObservationStatus,
) {
    init {
        require(INDEX_CODE.matches(index)) { "index must match ${INDEX_CODE.pattern}" }
        require(value.abs() <= MAX_ABS_PERCENT) { "an index value is a percentage within ±$MAX_ABS_PERCENT" }
        require(value.scale() <= MAX_SCALE) { "an index value has at most $MAX_SCALE decimal places" }
    }

    val sha256: String get() = hash(index, date, value, source)

    companion object {
        val INDEX_CODE = Regex("^[A-Z][A-Z0-9_]{1,31}$")
        val MAX_ABS_PERCENT = BigDecimal("100")
        const val MAX_SCALE = 8

        /** SHA-256 over `index|date|value(plain, trailing zeros stripped)|source`. */
        fun hash(index: String, date: LocalDate, value: BigDecimal, source: ObservationSource): String {
            val canonical = "$index|$date|${value.stripTrailingZeros().toPlainString()}|${source.name}"
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

enum class BatchKind { IMPORT, MANUAL_FIXING, FEED }

enum class BatchStatus { PENDING, APPROVED, REJECTED }

/**
 * A four-eyes unit of market data (ADR-0344 D5): an import file, or one manual fixing. Its rows are
 * PENDING until a DIFFERENT human approves the batch; an AI agent may never decide, and nobody but a
 * human or the feed may create one.
 */
data class ObservationBatch(
    val batchId: UUID,
    val kind: BatchKind,
    val rows: List<IndexObservation>,
    val reason: String,
    val fileSha256: String?,
    val createdBy: Actor,
    val createdAt: Instant,
    val status: BatchStatus = BatchStatus.PENDING,
    val decidedBy: Actor? = null,
    val decidedAt: Instant? = null,
    val decisionReason: String? = null,
) {
    init {
        require(rows.isNotEmpty() && rows.size <= MAX_ROWS) { "a batch has 1..$MAX_ROWS observations" }
        require(rows.all { it.batchId == batchId }) { "every row belongs to its batch" }
        require(rows.map { it.index to it.date }.toSet().size == rows.size) {
            "a batch names each (index, date) at most once"
        }
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a batch needs a reason (1..$MAX_REASON)" }
        require(kind != BatchKind.MANUAL_FIXING || rows.size == 1) { "a manual fixing is exactly one observation" }
    }

    fun approve(actor: Actor, at: Instant): ObservationBatch {
        decide(actor, "approve")
        return copy(
            status = BatchStatus.APPROVED,
            decidedBy = actor,
            decidedAt = at,
            rows = rows.map { it.copy(status = ObservationStatus.ACTIVE) },
        )
    }

    fun reject(actor: Actor, reason: String, at: Instant): ObservationBatch {
        decide(actor, "reject")
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a rejection needs a reason (1..$MAX_REASON)" }
        return copy(
            status = BatchStatus.REJECTED,
            decidedBy = actor,
            decidedAt = at,
            decisionReason = reason,
            rows = rows.map { it.copy(status = ObservationStatus.REJECTED) },
        )
    }

    private fun decide(actor: Actor, action: String) {
        if (actor.type != ActorType.HUMAN) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action market data")
        }
        check(status == BatchStatus.PENDING) { "cannot $action a $status batch (expected PENDING)" }
        if (actor.id == createdBy.id) {
            throw FourEyesViolationException("four-eyes: the creator of market data must not $action it")
        }
    }

    companion object {
        const val MAX_ROWS = 5000
        const val MAX_REASON = 1000

        @Suppress("LongParameterList")
        fun propose(
            id: UUID,
            kind: BatchKind,
            rows: List<Triple<String, LocalDate, BigDecimal>>,
            reason: String,
            fileSha256: String?,
            actor: Actor,
            at: Instant,
        ): ObservationBatch {
            val allowed = when (kind) {
                BatchKind.FEED -> actor.type == ActorType.SYSTEM
                else -> actor.type == ActorType.HUMAN
            }
            if (!allowed) {
                throw ActorNotPermittedException(
                    "a ${actor.type} principal may not propose $kind market data",
                )
            }
            val source = when (kind) {
                BatchKind.IMPORT -> ObservationSource.IMPORT
                BatchKind.MANUAL_FIXING -> ObservationSource.MANUAL
                BatchKind.FEED -> ObservationSource.FEED
            }
            return ObservationBatch(
                batchId = id,
                kind = kind,
                rows = rows.map { (index, date, value) ->
                    IndexObservation(index, date, value, source, id, ObservationStatus.PENDING)
                },
                reason = reason,
                fileSha256 = fileSha256,
                createdBy = actor,
                createdAt = at,
            )
        }
    }
}

/** The per-index quality thresholds (the `index` reference record, ADR-0344 D3/D5). */
data class QualityThresholds(val staleAfterBusinessDays: Int, val suspiciousJumpBp: Int)

/** The quality verdict of one (index, fixing date). [value] is set only when the gate passed. */
data class QualityVerdict(
    val index: String,
    val fixingDate: LocalDate,
    val value: BigDecimal?,
    val observation: IndexObservation?,
    val previous: BigDecimal?,
    val staleDays: Int?,
    val jumpBp: Int?,
    val missing: Boolean,
    val stale: Boolean,
    val suspicious: Boolean,
) {
    val passed: Boolean get() = !missing && !stale && !suspicious

    val reason: String?
        get() = when {
            missing -> "MISSING: no ACTIVE observation of $index on or before $fixingDate"
            stale -> "STALE: the latest observation is $staleDays business day(s) before $fixingDate"
            suspicious -> "SUSPICIOUS: a jump of $jumpBp bp against the previous observation"
            else -> null
        }
}

/**
 * The market-data quality gate (ADR-0344 D5), run BEFORE any fixing. Pure. It never substitutes a
 * value: a failed check blocks the (index, date), and the caller opens a case instead of fixing.
 *
 * - MISSING: no ACTIVE observation dated on or before the fixing date;
 * - STALE: the latest such observation is more than `staleAfterBusinessDays` business days (on the
 *   index's fixing calendar) before the fixing date — 0 means it must be dated on the fixing date;
 * - SUSPICIOUS: it differs from the observation before it by more than `suspiciousJumpBp` basis points.
 */
object QualityGate {
    private const val BASIS_POINTS_PER_PERCENT = 100
    private val BP_PER_PERCENT = BigDecimal(BASIS_POINTS_PER_PERCENT)

    fun check(
        index: String,
        fixingDate: LocalDate,
        active: List<IndexObservation>,
        thresholds: QualityThresholds,
        calendar: FinanceCalendar,
    ): QualityVerdict {
        val history = active.filter {
            it.index == index &&
                it.status == ObservationStatus.ACTIVE &&
                !it.date.isAfter(fixingDate)
        }
            .sortedBy { it.date }
        val latest = history.lastOrNull()
            ?: return QualityVerdict(index, fixingDate, null, null, null, null, null, true, false, false)
        val previous = history.dropLast(1).lastOrNull()?.value
        val staleDays = businessDaysBetween(latest.date, fixingDate, calendar)
        val jumpBp = previous?.let {
            latest.value.subtract(it).abs().multiply(BP_PER_PERCENT).setScale(0, RoundingMode.HALF_UP).toInt()
        }
        val stale = staleDays > thresholds.staleAfterBusinessDays
        val suspicious = jumpBp != null && jumpBp > thresholds.suspiciousJumpBp
        val passed = !stale && !suspicious
        return QualityVerdict(
            index = index,
            fixingDate = fixingDate,
            value = if (passed) latest.value else null,
            observation = latest,
            previous = previous,
            staleDays = staleDays,
            jumpBp = jumpBp,
            missing = false,
            stale = stale,
            suspicious = suspicious,
        )
    }

    /** Business days strictly after [from] up to and including [to]; 0 when [from] == [to]. */
    internal fun businessDaysBetween(from: LocalDate, to: LocalDate, calendar: FinanceCalendar): Int {
        var n = 0
        var d = from
        var guard = 0
        while (d.isBefore(to) && guard < MAX_SCAN) {
            d = d.plusDays(1)
            if (calendar.isBusinessDay(d)) n++
            guard++
        }
        return if (guard >= MAX_SCAN) Int.MAX_VALUE else n
    }

    private const val MAX_SCAN = 3660
}

enum class FixingCaseStatus { OPEN, RESOLVED }

/** A blocked (index, date): visible at `GET /fixings?status=BLOCKED` until resolved by a good observation. */
data class FixingCase(
    val index: String,
    val fixingDate: LocalDate,
    val status: FixingCaseStatus,
    val reason: String,
    val value: BigDecimal?,
    val previous: BigDecimal?,
    val staleDays: Int?,
    val jumpBp: Int?,
    val missing: Boolean,
    val openedAt: Instant,
    val resolvedAt: Instant? = null,
)
