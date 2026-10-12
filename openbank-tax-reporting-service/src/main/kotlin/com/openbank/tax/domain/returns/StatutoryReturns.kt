// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.returns

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.checkConflict
import com.openbank.tax.domain.model.requireValid
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/*
 * Statutory returns as jurisdiction data (ADR-0336).
 *
 * Nothing in this file names a country, a regulator or a return code: those live in the versioned
 * catalogue resources under `statutory-returns/`. The domain only knows the SHAPE every periodic
 * return shares — a scope, a periodicity, a deadline rule, a set of datapoints and the arithmetic
 * that must hold between them — and the lifecycle ADR-0180 already established for a filing.
 */

/** Who a return is about: the licensed company itself, or each fund it manages separately. */
enum class ReturnScope { COMPANY, FUND }

enum class Periodicity { MONTH, QUARTER, YEAR }

/** One reporting period, identified by its periodicity and its last day (the reference date). */
data class ReportingPeriod(val periodicity: Periodicity, val endDate: LocalDate) : Comparable<ReportingPeriod> {
    init {
        val valid = when (periodicity) {
            Periodicity.MONTH -> endDate == YearMonth.from(endDate).atEndOfMonth()
            Periodicity.QUARTER ->
                endDate.monthValue % MONTHS_PER_QUARTER == 0 &&
                    endDate == YearMonth.from(endDate).atEndOfMonth()
            Periodicity.YEAR -> endDate.monthValue == MONTHS_PER_YEAR && endDate.dayOfMonth == LAST_DAY_OF_DECEMBER
        }
        requireValid(valid) { "$endDate is not the last day of a $periodicity" }
    }

    /** `2026-07`, `2026-Q3`, `2026` — sortable within one periodicity and what an operator types. */
    val label: String get() = when (periodicity) {
        Periodicity.MONTH -> "%04d-%02d".format(endDate.year, endDate.monthValue)
        Periodicity.QUARTER -> "%04d-Q%d".format(endDate.year, endDate.monthValue / MONTHS_PER_QUARTER)
        Periodicity.YEAR -> "%04d".format(endDate.year)
    }

    /** The period immediately before this one. */
    fun previous(): ReportingPeriod = ReportingPeriod(
        periodicity,
        when (periodicity) {
            Periodicity.MONTH -> YearMonth.from(endDate).minusMonths(1).atEndOfMonth()
            Periodicity.QUARTER -> YearMonth.from(endDate).minusMonths(MONTHS_PER_QUARTER.toLong()).atEndOfMonth()
            Periodicity.YEAR -> endDate.minusYears(1)
        },
    )

    override fun compareTo(other: ReportingPeriod): Int = endDate.compareTo(other.endDate)

    companion object {
        private const val MONTHS_PER_QUARTER = 3
        private const val MONTHS_PER_YEAR = 12
        private const val LAST_DAY_OF_DECEMBER = 31
        private val QUARTER = Regex("""^(\d{4})-Q([1-4])$""")
        private val MONTH = Regex("""^(\d{4})-(\d{2})$""")
        private val YEAR = Regex("""^(\d{4})$""")

        /** The most recent period of [periodicity] that has fully ended strictly before [today]. */
        fun lastEndedBefore(periodicity: Periodicity, today: LocalDate): ReportingPeriod {
            var candidate = containing(periodicity, today)
            while (!candidate.endDate.isBefore(today)) candidate = candidate.previous()
            return candidate
        }

        fun containing(periodicity: Periodicity, date: LocalDate): ReportingPeriod = when (periodicity) {
            Periodicity.MONTH -> ReportingPeriod(periodicity, YearMonth.from(date).atEndOfMonth())
            Periodicity.QUARTER -> {
                val quarterEndMonth = ((date.monthValue - 1) / MONTHS_PER_QUARTER + 1) * MONTHS_PER_QUARTER
                ReportingPeriod(periodicity, YearMonth.of(date.year, quarterEndMonth).atEndOfMonth())
            }
            Periodicity.YEAR -> ReportingPeriod(
                periodicity,
                LocalDate.of(date.year, MONTHS_PER_YEAR, LAST_DAY_OF_DECEMBER),
            )
        }

        /** Parse an operator label; the periodicity must match the return being addressed. */
        fun parse(periodicity: Periodicity, label: String): ReportingPeriod {
            val parsed = when (periodicity) {
                Periodicity.MONTH -> MONTH.matchEntire(label)?.let { m ->
                    val month = m.groupValues[2].toInt()
                    if (month in
                        1..MONTHS_PER_YEAR
                    ) {
                        YearMonth.of(m.groupValues[1].toInt(), month).atEndOfMonth()
                    } else {
                        null
                    }
                }
                Periodicity.QUARTER -> QUARTER.matchEntire(label)?.let { m ->
                    YearMonth.of(m.groupValues[1].toInt(), m.groupValues[2].toInt() * MONTHS_PER_QUARTER).atEndOfMonth()
                }
                Periodicity.YEAR -> YEAR.matchEntire(label)?.let { m ->
                    LocalDate.of(m.groupValues[1].toInt(), MONTHS_PER_YEAR, LAST_DAY_OF_DECEMBER)
                }
            }
            requireValid(parsed != null) { "'$label' is not a $periodicity period label" }
            return ReportingPeriod(periodicity, parsed!!)
        }
    }
}

/** A signed term of a [ValidationRule.SumEquals] — `sign` is +1 or -1. */
data class SignedTerm(val datapoint: String, val sign: Int) {
    init {
        requireValid(sign == 1 || sign == -1) { "term sign must be +1 or -1, got $sign" }
    }
}

/** Arithmetic that must hold between a return's datapoints before it may be assembled. */
sealed interface ValidationRule {
    val id: String

    /** Every datapoint listed is >= 0. */
    data class NonNegative(override val id: String, val datapoints: List<String>) : ValidationRule

    /** `target == Σ sign·term`, compared exactly (BigDecimal.compareTo, scale-insensitive). */
    data class SumEquals(override val id: String, val target: String, val terms: List<SignedTerm>) : ValidationRule
}

/** One violated rule, stated so an operator can find the figure that broke it. */
data class ValidationFinding(val ruleId: String, val message: String)

/** A return definition from a catalogue: everything needed to assemble, validate and date it. */
data class ReturnDefinition(
    val code: String,
    val name: String,
    val scope: ReturnScope,
    val periodicity: Periodicity,
    val deadlineDaysAfterPeriodEnd: Int,
    val datapoints: List<String>,
    val rules: List<ValidationRule>,
    val legalBasis: String,
) {
    init {
        requireValid(code.isNotBlank()) { "return code must not be blank" }
        requireValid(deadlineDaysAfterPeriodEnd > 0) { "$code: deadline must be after the period end" }
        requireValid(datapoints.isNotEmpty()) { "$code: a return with no datapoints reports nothing" }
        requireValid(datapoints.toSet().size == datapoints.size) { "$code: duplicate datapoint ids" }
        val known = datapoints.toSet()
        rules.forEach { rule ->
            val referenced = when (rule) {
                is ValidationRule.NonNegative -> rule.datapoints
                is ValidationRule.SumEquals -> listOf(rule.target) + rule.terms.map { it.datapoint }
            }
            val unknown = referenced.filterNot { it in known }
            // A rule over a datapoint the return does not carry would be vacuously satisfied —
            // reject the catalogue rather than let a typo switch a control off.
            requireValid(unknown.isEmpty()) { "$code: rule ${rule.id} references unknown datapoints $unknown" }
        }
    }

    fun dueDate(period: ReportingPeriod): LocalDate = period.endDate.plusDays(deadlineDaysAfterPeriodEnd.toLong())

    /**
     * Every finding for [values]; empty means valid. Missing datapoints are findings, never zeroes:
     * a regulator reading 0 cannot tell "nothing happened" from "we did not have the figure".
     */
    fun validate(values: Map<String, BigDecimal>): List<ValidationFinding> {
        val missing = datapoints.filterNot { it in values }
        val unexpected = values.keys.filterNot { it in datapoints.toSet() }
        val findings = mutableListOf<ValidationFinding>()
        if (missing.isNotEmpty()) findings += ValidationFinding("REQUIRED", "$code: missing datapoints $missing")
        if (unexpected.isNotEmpty()) findings += ValidationFinding("SCHEMA", "$code: unexpected datapoints $unexpected")
        if (missing.isNotEmpty()) return findings
        rules.forEach { rule ->
            when (rule) {
                is ValidationRule.NonNegative ->
                    rule.datapoints
                        .filter { values.getValue(it).signum() < 0 }
                        .forEach {
                            findings +=
                                ValidationFinding(rule.id, "$code: $it must not be negative (${values.getValue(it)})")
                        }
                is ValidationRule.SumEquals -> {
                    val sum = rule.terms.fold(BigDecimal.ZERO) { acc, t ->
                        acc.add(values.getValue(t.datapoint).multiply(BigDecimal(t.sign)))
                    }
                    val target = values.getValue(rule.target)
                    if (target.compareTo(sum) != 0) {
                        findings += ValidationFinding(rule.id, "$code: ${rule.target}=$target but terms sum to $sum")
                    }
                }
            }
        }
        return findings
    }
}

/** A versioned, jurisdiction-specific set of return definitions (one resource file). */
data class ReturnCatalogue(
    val id: String,
    val version: Int,
    val jurisdiction: String,
    val wireFormatVerified: Boolean,
    val returns: List<ReturnDefinition>,
) {
    init {
        requireValid(version > 0) { "catalogue $id: version must be positive" }
        requireValid(returns.map { it.code }.toSet().size == returns.size) { "catalogue $id: duplicate return codes" }
    }

    fun definition(code: String): ReturnDefinition? = returns.firstOrNull { it.code == code }
}

/** Thrown when assembled figures break a catalogue rule; mapped to 422 with every finding. */
class ReturnValidationException(val findings: List<ValidationFinding>) :
    RuntimeException("Return failed validation: " + findings.joinToString("; ") { it.message })

enum class ReturnStatus { ASSEMBLED, APPROVED, SUBMITTED }

/**
 * One revision of one return for one entity and period (ADR-0336).
 *
 * ASSEMBLED → APPROVED (four-eyes, attests the content hash) → SUBMITTED (with the regulator's
 * reference, refused if the content no longer matches what was attested). A correction is a new
 * [revision]; a submitted return is never edited in place.
 */
data class StatutoryReturn(
    val id: UUID,
    val catalogueId: String,
    val catalogueVersion: Int,
    val returnCode: String,
    val entityId: String,
    val period: ReportingPeriod,
    val revision: Int,
    val status: ReturnStatus,
    val datapoints: Map<String, BigDecimal>,
    val contentHash: String,
    val dueDate: LocalDate,
    val assembledBy: String,
    val assembledAt: Instant,
    val approvedBy: String? = null,
    val approvedAt: Instant? = null,
    val attestedHash: String? = null,
    val submittedBy: String? = null,
    val submittedAt: Instant? = null,
    val submissionReference: String? = null,
    val version: Long = 0L,
) {
    /** The approver must not be the assembler; approval pins the content hash as the attestation. */
    fun approve(by: String, at: Instant): StatutoryReturn {
        checkConflict(status == ReturnStatus.ASSEMBLED) {
            "$returnCode ${period.label} is not ASSEMBLED (status=$status)"
        }
        requireValid(by.isNotBlank()) { "Approval requires an actor" }
        checkConflict(by != assembledBy) {
            "Four-eyes violation: $by assembled $returnCode ${period.label} and may not approve it"
        }
        return copy(
            status = ReturnStatus.APPROVED,
            approvedBy = by,
            approvedAt = at,
            attestedHash = contentHash,
            version =
            version + 1,
        )
    }

    /**
     * Record the submission. Refused unless the content still hashes to what was attested — the
     * stored figures and the approved figures must be the same figures.
     */
    fun submit(reference: String, by: String, at: Instant): StatutoryReturn {
        checkConflict(status == ReturnStatus.APPROVED) {
            "$returnCode ${period.label} is not APPROVED (status=$status) — approve it before submitting"
        }
        requireValid(reference.isNotBlank()) { "A submission reference is required" }
        requireValid(by.isNotBlank()) { "Submission requires an actor" }
        val recomputed = contentHash(catalogueId, catalogueVersion, returnCode, entityId, period, revision, datapoints)
        if (attestedHash == null || recomputed != attestedHash) {
            throw TaxConflictException(
                "$returnCode ${period.label}: content does not match the attested hash — refusing to submit unattested figures",
            )
        }
        return copy(
            status = ReturnStatus.SUBMITTED,
            submittedBy = by,
            submittedAt = at,
            submissionReference = reference,
            version =
            version + 1,
        )
    }

    fun isOverdueAt(date: LocalDate): Boolean = status != ReturnStatus.SUBMITTED && date.isAfter(dueDate)

    companion object {
        /** Validate and assemble; invalid figures never become a stored return. */
        @Suppress("LongParameterList")
        fun assemble(
            catalogue: ReturnCatalogue,
            definition: ReturnDefinition,
            entityId: String,
            period: ReportingPeriod,
            revision: Int,
            values: Map<String, BigDecimal>,
            by: String,
            at: Instant,
        ): StatutoryReturn {
            requireValid(period.periodicity == definition.periodicity) {
                "${definition.code} is ${definition.periodicity}, not ${period.periodicity}"
            }
            requireValid(by.isNotBlank()) { "Assembly requires an actor" }
            val findings = definition.validate(values)
            if (findings.isNotEmpty()) throw ReturnValidationException(findings)
            return StatutoryReturn(
                id = Ids.newId(),
                catalogueId = catalogue.id,
                catalogueVersion = catalogue.version,
                returnCode = definition.code,
                entityId = entityId,
                period = period,
                revision = revision,
                status = ReturnStatus.ASSEMBLED,
                datapoints = values.toSortedMap(),
                contentHash = contentHash(
                    catalogue.id,
                    catalogue.version,
                    definition.code,
                    entityId,
                    period,
                    revision,
                    values,
                ),
                dueDate = definition.dueDate(period),
                assembledBy = by,
                assembledAt = at,
            )
        }

        /** Canonical, order-independent, scale-independent rendering of the attested content. */
        @Suppress("LongParameterList")
        fun canonicalContent(
            catalogueId: String,
            catalogueVersion: Int,
            returnCode: String,
            entityId: String,
            period: ReportingPeriod,
            revision: Int,
            values: Map<String, BigDecimal>,
        ): String = buildString {
            append("catalogue=").append(catalogueId).append('@').append(catalogueVersion).append('\n')
            append("return=").append(returnCode).append('\n')
            append("entity=").append(entityId).append('\n')
            append("period=").append(period.periodicity).append(':').append(period.label).append('\n')
            append("revision=").append(revision).append('\n')
            values.toSortedMap().forEach { (k, v) ->
                append(k).append('=').append(v.stripTrailingZeros().toPlainString()).append('\n')
            }
        }

        @Suppress("LongParameterList")
        fun contentHash(
            catalogueId: String,
            catalogueVersion: Int,
            returnCode: String,
            entityId: String,
            period: ReportingPeriod,
            revision: Int,
            values: Map<String, BigDecimal>,
        ): String = MessageDigest.getInstance("SHA-256")
            .digest(
                canonicalContent(
                    catalogueId,
                    catalogueVersion,
                    returnCode,
                    entityId,
                    period,
                    revision,
                    values,
                ).toByteArray(),
            )
            .joinToString("") { "%02x".format(it) }
    }
}
