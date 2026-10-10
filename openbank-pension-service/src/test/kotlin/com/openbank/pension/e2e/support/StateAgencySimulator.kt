// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e.support

import java.math.BigDecimal

/**
 * The state agency (MF ČR) on the other side of the CZ state-contribution channel, modelled as a
 * pure function of the filed documents. It reads the claim application and the return report the
 * service rendered in `cz-mf-state-contribution-v1` (a placeholder format, see
 * `docs/research/cz-state-pension-contribution.md` T2). It answers with the processing result an
 * operator uploads back. It knows only the wire format, never the service's classes, so a change
 * to the filed format breaks the journey the same way it would break the agency.
 *
 * Application: `H;FORMAT;APPLICATION;IČO;year;quarter;count;total`, then
 * `L;claimId;contractRef;participantRef;YYYY-MM;contribution;requested` lines.
 * Result: `R;FORMAT;RESULT;year;quarter;aggregatePaid`, then `P;claimId;paid`,
 * `Q;claimId;paid;code` (partial) or `X;claimId;code` (not paid).
 * Return report: `H;FORMAT;RETURNS;IČO;YYYY-MM;count;total`, then `V;returnId;contractRef;cause;amount;month`.
 * Return result: `R;FORMAT;RETURNS-RESULT;YYYY-MM`, then `C;returnId` or `E;returnId;code`.
 */
object StateAgencySimulator {

    const val FORMAT = "cz-mf-state-contribution-v1"

    data class FiledClaim(
        val claimId: String,
        val contractReference: String,
        val period: String,
        val contribution: String,
        val claimed: String,
    )

    data class FiledReturn(val returnId: String, val contractReference: String, val cause: String, val amount: String)

    fun parse(payload: String): List<FiledClaim> {
        val records = records(payload)
        val header = records.first()
        require(header.size == APP_HEADER_FIELDS && header[1] == FORMAT && header[2] == "APPLICATION") {
            "not a $FORMAT application header: $header"
        }
        val lines = records.drop(1).map { p ->
            require(p[0] == "L" && p.size == APP_LINE_FIELDS) { "application line malformed: $p" }
            FiledClaim(p[1], p[2], p[4], p[5], p[6])
        }
        require(header[6].toInt() == lines.size) { "header count ${header[6]} != ${lines.size} lines" }
        require(
            BigDecimal(header[7]).compareTo(
                lines.sumOf {
                    BigDecimal(it.claimed)
                },
            ) == 0,
        ) { "header total mismatch" }
        return lines
    }

    /**
     * MF's processing result. Every claim is paid in full, except those in [reject] (claim id →
     * reason code) and those in [partial] (claim id → amount actually paid). One aggregate payment
     * covers them all.
     */
    fun receipt(
        payload: String,
        reject: Map<String, String> = emptyMap(),
        partial: Map<String, BigDecimal> = emptyMap(),
    ): String {
        val header = records(payload).first()
        val lines = parse(payload).map { c ->
            when {
                reject.containsKey(c.claimId) -> "X;${c.claimId};${reject.getValue(c.claimId)}" to BigDecimal.ZERO
                partial.containsKey(c.claimId) ->
                    "Q;${c.claimId};${partial.getValue(c.claimId).toPlainString()};AMOUNT_RECOMPUTED" to
                        partial.getValue(c.claimId)
                else -> "P;${c.claimId};${c.claimed}" to BigDecimal(c.claimed)
            }
        }
        val aggregate = lines.fold(BigDecimal.ZERO) { a, l -> a + l.second }
        return (
            listOf("R;$FORMAT;RESULT;${header[4]};${header[5]};${aggregate.toPlainString()}") +
                lines.map { it.first }
            )
            .joinToString("\n")
    }

    fun parseReturns(payload: String): List<FiledReturn> {
        val records = records(payload)
        val header = records.first()
        require(header[1] == FORMAT && header[2] == "RETURNS") { "not a $FORMAT return report: $header" }
        return records.drop(1).map { p ->
            require(p[0] == "V" && p.size == RETURN_LINE_FIELDS) { "return line malformed: $p" }
            FiledReturn(p[1], p[2], p[3], p[4])
        }
    }

    /** MF's answer to a return report: every line confirmed, except those in [refuse] (return id → code). */
    fun returnResult(payload: String, refuse: Map<String, String> = emptyMap()): String {
        val month = records(payload).first()[4]
        return (
            listOf("R;$FORMAT;RETURNS-RESULT;$month") + parseReturns(payload).map { r ->
                refuse[r.returnId]?.let { "E;${r.returnId};$it" } ?: "C;${r.returnId}"
            }
            ).joinToString("\n")
    }

    private fun records(payload: String) = payload.lines().map(String::trim).filter(String::isNotEmpty).map {
        it.split(";")
    }

    private const val APP_HEADER_FIELDS = 8
    private const val APP_LINE_FIELDS = 7
    private const val RETURN_LINE_FIELDS = 6
}
