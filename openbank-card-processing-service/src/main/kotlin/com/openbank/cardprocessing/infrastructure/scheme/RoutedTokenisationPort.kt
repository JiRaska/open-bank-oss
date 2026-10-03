// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkToken
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.cards.scheme.TokenRequestor
import com.openbank.libs.domain.cards.scheme.TokenisationPort
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Picks which binding answers a tokenisation call (ADR-0283 D1).
 *
 * ## Why there is no Visa or Mastercard client behind this
 *
 * Both networks put tokenisation behind a **commercial contract**: VTS and MDES have no free
 * developer sandbox, which the capability registry records as `availability: contract`. An adapter
 * written against documentation nobody here can open would be code that can never be executed,
 * never verified, and green in every test — the exact shape of the finrep call to a ledger route
 * that had never existed (#2269). The BIN adapters exist precisely because that capability *does*
 * have a free sandbox to build against.
 *
 * So the vendor names are **selectable and honest**: choosing one gets
 * [SchemeFailure.NOT_BOUND] with a reason naming the contract, rather than a call to a guessed
 * route. When a contract exists, the adapter lands here and this branch goes away — and until then
 * the gap is visible at the call site instead of only in a document.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class RoutedTokenisationPort(
    private val simulator: SimulatedTokenisationAdapter,
    @ConfigProperty(name = "openbank.card-processing.scheme.tokenisation", defaultValue = "simulator")
    private val binding: String,
) : TokenisationPort {

    override suspend fun provision(cardReference: String, requestor: TokenRequestor): SchemeResult<NetworkToken> =
        when (val chosen = binding.lowercase()) {
            SIMULATOR -> simulator.provision(cardReference, requestor)
            else -> contractRequired(chosen)
        }

    override suspend fun listTokens(cardReference: String): SchemeResult<List<NetworkToken>> =
        when (val chosen = binding.lowercase()) {
            SIMULATOR -> simulator.listTokens(cardReference)
            else -> contractRequired(chosen)
        }

    override suspend fun changeStatus(tokenReference: String, status: NetworkTokenStatus): SchemeResult<NetworkToken> =
        when (val chosen = binding.lowercase()) {
            SIMULATOR -> simulator.changeStatus(tokenReference, status)
            else -> contractRequired(chosen)
        }

    private fun <T> contractRequired(chosen: String): SchemeResult<T> = SchemeResult.Unanswered(
        SchemeFailure.NOT_BOUND,
        schemeFor(chosen),
        "tokenisation on $chosen needs a scheme contract (VTS / MDES have no free sandbox), so no " +
            "adapter is built against it — see openbank-libs/governance/card-capabilities.yaml",
    )

    /**
     * The scheme a caller ASKED for, so the failure names the binding that could not answer rather
     * than the one that happened to be wired. A NOT_BOUND attributed to the simulator would read as
     * the simulator being broken.
     */
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
