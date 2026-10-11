// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e.support

/**
 * The state agency on the other side of the incentive claim channel, as a pure function of the
 * filed batch: it reads the claim file the service rendered (`agency-monthly-batch-v0`) and answers
 * the receipt file the operator uploads back. It knows the wire format only — never the service's
 * classes — so a change to the filed format breaks the journey the way it would break the agency.
 *
 * Filed line: `claimId;contractReference;period;basis;claimedAmount` after a header line.
 * Receipt line: `claimId;ACCEPTED;amount` or `claimId;REJECTED;reason`.
 */
object StateAgencySimulator {

    private const val FORMAT = "agency-monthly-batch-v0"

    data class FiledClaim(val claimId: String, val contractReference: String, val period: String, val claimed: String)

    fun parse(payload: String): List<FiledClaim> = payload.lines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith(FORMAT) }
        .map { line ->
            val p = line.split(";")
            require(p.size == FILED_FIELDS) { "filed line has ${p.size} fields, expected $FILED_FIELDS: $line" }
            FiledClaim(p[0], p[1], p[2], p[4])
        }

    /** Accept every claim at the amount claimed, except those [reject] names (claim id -> reason). */
    fun receipt(payload: String, reject: Map<String, String> = emptyMap()): String = (
        listOf("$FORMAT;receipt") + parse(payload).map { c ->
            reject[c.claimId]?.let { "${c.claimId};REJECTED;$it" } ?: "${c.claimId};ACCEPTED;${c.claimed}"
        }
        ).joinToString("\n")

    private const val FILED_FIELDS = 5
}
