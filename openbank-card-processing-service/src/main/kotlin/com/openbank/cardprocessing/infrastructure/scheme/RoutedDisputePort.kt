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
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Picks which binding answers a dispute call (ADR-0283 D1).
 *
 * Same shape and same reasoning as [RoutedTokenisationPort]: VROL and Mastercom are behind a
 * commercial contract, so a vendor adapter would be unexecutable code written from documentation
 * nobody here can open. Choosing a network name returns [SchemeFailure.NOT_BOUND] naming the
 * contract, which puts the gap at the call site rather than only in a document.
 *
 * Worth stating separately for disputes: the bank-side lifecycle is ADR-0117 and already exists.
 * This port is the wire to the NETWORK, and nothing about ADR-0117 depends on it being bound —
 * a bank can run its own dispute process while the scheme leg stays manual.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class RoutedDisputePort(
    private val simulator: SimulatedDisputeAdapter,
    @ConfigProperty(name = "openbank.card-processing.scheme.dispute", defaultValue = "simulator")
    private val binding: String,
) : DisputePort {

    override suspend fun open(
        authorizationNetworkReference: String,
        reasonCode: String,
        amountMinorUnits: Long,
        currencyCode: String,
    ): SchemeResult<SchemeDispute> = when (val chosen = binding.lowercase()) {
        SIMULATOR -> simulator.open(authorizationNetworkReference, reasonCode, amountMinorUnits, currencyCode)
        else -> contractRequired(chosen)
    }

    override suspend fun submitEvidence(evidence: DisputeEvidence): SchemeResult<SchemeDispute> =
        when (val chosen = binding.lowercase()) {
            SIMULATOR -> simulator.submitEvidence(evidence)
            else -> contractRequired(chosen)
        }

    override suspend fun status(networkCaseId: String): SchemeResult<SchemeDispute> =
        when (val chosen = binding.lowercase()) {
            SIMULATOR -> simulator.status(networkCaseId)
            else -> contractRequired(chosen)
        }

    private fun contractRequired(chosen: String): SchemeResult<SchemeDispute> = SchemeResult.Unanswered(
        SchemeFailure.NOT_BOUND,
        schemeFor(chosen),
        "disputes on $chosen need a scheme contract (VROL / Mastercom have no free sandbox), so no " +
            "adapter is built against it — see openbank-libs/governance/card-capabilities.yaml",
    )

    private fun schemeFor(chosen: String): CardScheme = when (chosen) {
        VISA -> CardScheme.VISA
        MASTERCARD -> CardScheme.MASTERCARD
        else -> CardScheme.SIMULATOR
    }

    private companion object {
        const val SIMULATOR = "simulator"
        const val VISA = "visa"
        const val MASTERCARD = "mastercard"
    }
}
