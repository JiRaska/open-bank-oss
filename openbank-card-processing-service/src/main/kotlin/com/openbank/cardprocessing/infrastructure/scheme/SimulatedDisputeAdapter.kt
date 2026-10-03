// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.DisputeEvidence
import com.openbank.libs.domain.cards.scheme.DisputePort
import com.openbank.libs.domain.cards.scheme.SchemeDispute
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-repo binding of [DisputePort] (ADR-0283 phase 2, #8810).
 *
 * ## What it simulates, and what it refuses to invent
 *
 * The network's own case lifecycle: a case opens with a respond-by date, evidence moves it to
 * `EVIDENCE_SUBMITTED`, and its status can be read back. That is enough to exercise every branch a
 * caller has, and it is the part that is the same across both networks.
 *
 * It does **not** invent scheme reason codes. [SchemeDispute.reasonCode] is carried through exactly
 * as the caller supplied it, because the code vocabulary is per-network, versioned, and published
 * under contract — a simulator that made one up would teach a caller a code the network rejects,
 * and the rejection would arrive months later at a real chargeback deadline.
 *
 * ## The respond-by date is a simulation, and says so
 *
 * Real scheme deadlines depend on the reason code, the region and the calendar. [RESPONSE_DAYS] is
 * a flat number and is deliberately obvious as a placeholder; a caller that needs the real deadline
 * needs the real network, and anything computed here would look authoritative and be wrong.
 */
@ApplicationScoped
class SimulatedDisputeAdapter(private val clock: Clock) : DisputePort {

    private val cases = ConcurrentHashMap<String, SchemeDispute>()

    override suspend fun open(
        authorizationNetworkReference: String,
        reasonCode: String,
        amountMinorUnits: Long,
        currencyCode: String,
    ): SchemeResult<SchemeDispute> {
        if (reasonCode.isBlank()) {
            return SchemeResult.Unanswered(
                SchemeFailure.MALFORMED,
                CardScheme.SIMULATOR,
                "reasonCode is blank; the network requires one and this simulator will not invent it",
            )
        }
        if (amountMinorUnits <= 0) {
            return SchemeResult.Unanswered(
                SchemeFailure.MALFORMED,
                CardScheme.SIMULATOR,
                "disputed amount must be positive",
            )
        }
        val dispute = SchemeDispute(
            // Ids.randomId() — see SimulatedTokenisationAdapter: an opaque reference, not an
            // indexed key, and ADR-0106 asks for the choice to be legible rather than incidental.
            networkCaseId = "sim-case-${Ids.randomId()}",
            // Carried through, never mapped: the code vocabulary belongs to the network.
            reasonCode = reasonCode,
            amountMinorUnits = amountMinorUnits,
            currencyCode = currencyCode.uppercase(),
            respondByDate = LocalDate.now(clock).plusDays(RESPONSE_DAYS),
            status = STATUS_OPEN,
        )
        cases[dispute.networkCaseId] = dispute
        return SchemeResult.Answered(dispute, CardScheme.SIMULATOR)
    }

    override suspend fun submitEvidence(evidence: DisputeEvidence): SchemeResult<SchemeDispute> {
        val existing = cases[evidence.networkCaseId]
            ?: return SchemeResult.Unanswered(
                SchemeFailure.NOT_FOUND,
                CardScheme.SIMULATOR,
                "no simulated case ${evidence.networkCaseId}",
            )
        if (evidence.documentReference.isBlank()) {
            return SchemeResult.Unanswered(
                SchemeFailure.MALFORMED,
                CardScheme.SIMULATOR,
                "evidence must reference a document",
            )
        }
        val updated = existing.copy(status = STATUS_EVIDENCE_SUBMITTED)
        cases[updated.networkCaseId] = updated
        return SchemeResult.Answered(updated, CardScheme.SIMULATOR)
    }

    override suspend fun status(networkCaseId: String): SchemeResult<SchemeDispute> =
        cases[networkCaseId]?.let { SchemeResult.Answered(it, CardScheme.SIMULATOR) }
            ?: SchemeResult.Unanswered(
                SchemeFailure.NOT_FOUND,
                CardScheme.SIMULATOR,
                "no simulated case $networkCaseId",
            )

    private companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_EVIDENCE_SUBMITTED = "EVIDENCE_SUBMITTED"

        /**
         * Flat and deliberately obvious as a placeholder. Real scheme response windows depend on
         * the reason code, the region and the calendar, and a number computed here would look
         * authoritative while being wrong at a real deadline.
         */
        const val RESPONSE_DAYS = 30L
    }
}
