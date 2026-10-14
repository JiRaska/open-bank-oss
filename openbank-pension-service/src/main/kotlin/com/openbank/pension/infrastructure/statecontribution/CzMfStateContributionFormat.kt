// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.pension.application.port.out.ClaimReceiptLine
import com.openbank.pension.domain.statecontribution.CzClaimReasonCode
import com.openbank.pension.domain.statecontribution.CzStateContributionCalendar
import com.openbank.pension.domain.statecontribution.ReturnReportLine
import com.openbank.pension.domain.statecontribution.ReturnResultLine
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID

/**
 * Wire format `cz-mf-state-contribution-v1`. **This is a PLACEHOLDER format.** MF's technical
 * specification for the DPS state-contribution exchange is not public (research T2,
 * `docs/research/cz-state-pension-contribution.md`). This format carries the facts ZDPS §16(3)
 * and §18(4) require, so the lifecycle around it is real and swapping the format stays local to
 * this file. Records are semicolon separated, one per line, and the first field is the record type.
 *
 * Claim application (one per quarter, §16(2)–(3)):
 * ```
 * H;cz-mf-state-contribution-v1;APPLICATION;<IČO>;<year>;<quarter>;<lineCount>;<totalRequested>
 * L;<claimId>;<contractReference>;<participantRef>;<month YYYY-MM>;<participantContribution>;<requested>
 * ```
 * The identity fields of §16(3) (birth number, postcode, EU state) are not held by pension-service.
 * `participantRef` stands in for them until a follow-up resolves identity data from party-service.
 *
 * Processing result for an application (P1/P3, one aggregate payment split per line):
 * ```
 * R;cz-mf-state-contribution-v1;RESULT;<year>;<quarter>;<aggregatePaid>
 * P;<claimId>;<paid>                      fully paid
 * Q;<claimId>;<paid>;<reasonCode>         paid in part (recomputed)
 * X;<claimId>;<reasonCode>                not paid
 * ```
 * The aggregate must equal the sum of the line payments. A result whose lines do not add up to the
 * money MF actually paid is refused whole.
 *
 * Monthly return report (§18(4)) and its result (§18(6)):
 * ```
 * H;cz-mf-state-contribution-v1;RETURNS;<IČO>;<YYYY-MM>;<lineCount>;<total>
 * V;<returnId>;<contractReference>;<cause>;<amount>;<claimMonth or ->
 * R;cz-mf-state-contribution-v1;RETURNS-RESULT;<YYYY-MM>
 * C;<returnId>                            confirmed
 * E;<returnId>;<reasonCode>               refused, re-report after correction
 * ```
 */
object CzMfStateContributionFormat {
    const val FORMAT = "cz-mf-state-contribution-v1"
    private const val SEP = ";"

    data class ApplicationLine(
        val claimId: UUID,
        val contractReference: String,
        val participantRef: String,
        val month: YearMonth,
        val contribution: BigDecimal,
        val requested: BigDecimal,
    )

    fun application(ico: String, quarterMonth: YearMonth, lines: List<ApplicationLine>): String {
        val total = lines.fold(BigDecimal.ZERO) { a, l -> a + l.requested }
        val header = listOf(
            "H",
            FORMAT,
            "APPLICATION",
            ico,
            quarterMonth.year,
            CzStateContributionCalendar.quarterOf(quarterMonth),
            lines.size,
            total.toPlainString(),
        )
        val body = lines.sortedWith(compareBy({ it.month }, { it.contractReference })).map {
            listOf(
                "L",
                it.claimId,
                it.contractReference,
                it.participantRef,
                it.month,
                it.contribution.toPlainString(),
                it.requested.toPlainString(),
            )
        }
        return (listOf(header) + body).joinToString("\n") { it.joinToString(SEP) }
    }

    /** Parses an application result into receipt lines; refuses one whose aggregate does not add up. */
    fun parseApplicationResult(payload: String): List<ClaimReceiptLine> {
        val records = records(payload)
        val header = records.firstOrNull()
        require(header != null && header.size == RESULT_HEADER_FIELDS && header[0] == "R" && header[2] == "RESULT") {
            "result must start with an R;$FORMAT;RESULT header"
        }
        require(header[1] == FORMAT) { "result format '${header[1]}' is not $FORMAT" }
        val aggregate = amount(header[AGGREGATE_FIELD], "aggregate")
        val lines = records.drop(1).mapIndexed { i, r -> resultLine(i + 2, r) }
        val paid = lines.fold(BigDecimal.ZERO) { a, l -> a + (l.amount ?: BigDecimal.ZERO) }
        require(paid.compareTo(aggregate) == 0) {
            "result lines pay $paid but the aggregate payment is $aggregate; refusing the whole result"
        }
        require(lines.map { it.claimId }.toSet().size == lines.size) { "result names a claim twice" }
        return lines
    }

    fun returnReport(ico: String, month: YearMonth, lines: List<ReturnReportLine>): String {
        val total = lines.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
        val header = listOf("H", FORMAT, "RETURNS", ico, month, lines.size, total.toPlainString())
        val body = lines.map {
            listOf(
                "V",
                it.returnId,
                it.contractReference,
                it.cause.name,
                it.amount.toPlainString(),
                it.claimMonth ?: "-",
            )
        }
        return (listOf(header) + body).joinToString("\n") { it.joinToString(SEP) }
    }

    fun parseReturnResult(payload: String): List<ReturnResultLine> {
        val records = records(payload)
        val header = records.firstOrNull()
        require(
            header != null &&
                header.size >= RETURN_RESULT_HEADER_MIN &&
                header[0] == "R" &&
                header[2] == "RETURNS-RESULT",
        ) {
            "return result must start with an R;$FORMAT;RETURNS-RESULT header"
        }
        require(header[1] == FORMAT) { "result format '${header[1]}' is not $FORMAT" }
        return records.drop(1).mapIndexed { i, r ->
            val n = i + 2
            when (r[0]) {
                "C" -> {
                    require(r.size == 2) { "line $n: C needs 2 fields" }
                    ReturnResultLine.Confirmed(uuid(r[1], n))
                }
                "E" -> {
                    require(r.size == WITH_CODE) { "line $n: E needs $WITH_CODE fields" }
                    ReturnResultLine.Refused(uuid(r[1], n), CzClaimReasonCode.parse(r[2]))
                }
                else -> throw IllegalArgumentException("line $n: unknown record type '${r[0]}'")
            }
        }
    }

    private fun resultLine(n: Int, r: List<String>): ClaimReceiptLine = when (r[0]) {
        "P" -> {
            require(r.size == WITH_CODE) { "line $n: P needs $WITH_CODE fields" }
            ClaimReceiptLine(uuid(r[1], n), true, positive(r[2], n), null)
        }
        "Q" -> {
            require(r.size == PARTIAL_FIELDS) { "line $n: Q needs $PARTIAL_FIELDS fields" }
            ClaimReceiptLine(uuid(r[1], n), true, positive(r[2], n), CzClaimReasonCode.parse(r[PARTIAL_CODE]).name)
        }
        "X" -> {
            require(r.size == WITH_CODE) { "line $n: X needs $WITH_CODE fields" }
            val code = CzClaimReasonCode.parse(r[2])
            ClaimReceiptLine(uuid(r[1], n), false, null, "${code.name}: ${code.description}")
        }
        else -> throw IllegalArgumentException("line $n: unknown record type '${r[0]}'")
    }

    private fun records(payload: String): List<List<String>> =
        payload.lines().map(String::trim).filter(String::isNotEmpty).map { it.split(SEP) }

    private fun uuid(raw: String, n: Int): UUID =
        runCatching { UUID.fromString(raw) }.getOrElse { throw IllegalArgumentException("line $n: bad id") }

    private fun amount(raw: String, what: String): BigDecimal =
        raw.toBigDecimalOrNull() ?: throw IllegalArgumentException("bad $what amount '$raw'")

    private fun positive(raw: String, n: Int): BigDecimal = amount(raw, "line $n").also {
        require(it.signum() > 0) { "line $n: a paid amount must be positive; an unpaid line is an X record" }
    }

    private const val RESULT_HEADER_FIELDS = 6
    private const val AGGREGATE_FIELD = 5
    private const val RETURN_RESULT_HEADER_MIN = 3
    private const val WITH_CODE = 3
    private const val PARTIAL_FIELDS = 4
    private const val PARTIAL_CODE = 3
}
