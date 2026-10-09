// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.adapter

import com.openbank.pension.application.port.out.ClaimReceiptLine
import com.openbank.pension.application.port.out.RenderedClaimBatch
import com.openbank.pension.application.port.out.StateIncentiveClaimPort
import com.openbank.pension.domain.incentive.IncentiveClaim
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID

/**
 * Claim channel `agency-monthly-batch-v0` (ADR-0334 S3): one monthly batch of state-contribution
 * claims filed with the state agency, the shape the CZ DPS pack names.
 *
 * THE WIRE FORMAT IS A PLACEHOLDER. The real filing is a structured file in the agency's own
 * published specification, exchanged over its own channel; neither is implemented here. This
 * renders a semicolon-separated document carrying the same facts — claim id, contract reference,
 * period, basis, claimed amount — so the lifecycle around it (batching, evidence, reconciliation)
 * is real and the format swap is local to this class. It does not transmit anything; the payload
 * is stored on the batch as filing evidence and the operator files it. Needs: the agency's
 * current file specification and channel, and legal review, before any production use.
 *
 * Receipt format (same placeholder): `claimId;ACCEPTED;amount` or `claimId;REJECTED;reason`.
 */
@ApplicationScoped
class AgencyMonthlyBatchClaimAdapter : StateIncentiveClaimPort {

    override val claimFormat: String = FORMAT

    override suspend fun submit(period: YearMonth, claims: List<IncentiveClaim>, references: Map<UUID, String>): RenderedClaimBatch {
        val lines = claims.map { c ->
            listOf(c.id, references.getValue(c.contractId), c.period, c.basis.toPlainString(), c.claimedAmount.toPlainString())
                .joinToString(SEP)
        }
        val header = "$FORMAT${SEP}period=$period${SEP}count=${claims.size}${SEP}" +
            "total=${claims.fold(BigDecimal.ZERO) { a, c -> a + c.claimedAmount }.toPlainString()}"
        return RenderedClaimBatch((listOf(header) + lines).joinToString("\n"), channelReference = null)
    }

    override fun parseReceipt(payload: String): List<ClaimReceiptLine> =
        payload.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(FORMAT) }.mapIndexed { i, line ->
            val parts = line.split(SEP)
            require(parts.size == RECEIPT_FIELDS) { "receipt line ${i + 1} must have $RECEIPT_FIELDS fields" }
            val id = runCatching { UUID.fromString(parts[0]) }.getOrElse { throw IllegalArgumentException("receipt line ${i + 1}: bad claim id") }
            when (parts[1]) {
                "ACCEPTED" -> ClaimReceiptLine(id, true, parts[2].toBigDecimalOrNull() ?: throw IllegalArgumentException("receipt line ${i + 1}: bad amount"), null)
                "REJECTED" -> ClaimReceiptLine(id, false, null, parts[2])
                else -> throw IllegalArgumentException("receipt line ${i + 1}: unknown outcome '${parts[1]}'")
            }
        }

    companion object {
        const val FORMAT = "agency-monthly-batch-v0"
        private const val SEP = ";"
        private const val RECEIPT_FIELDS = 3
    }
}
