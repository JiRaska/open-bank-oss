// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.domain.cards.scheme.BinAttributes
import com.openbank.libs.domain.cards.scheme.BinLookupPort
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Picks which network's adapter answers a BIN lookup (ADR-0283 D1).
 *
 * ## This is where "the bank chooses the network" becomes a real thing
 *
 * `openbank.card-processing.scheme.bin-lookup` names the binding: `simulator`, `visa` or
 * `mastercard`. Changing the network is a configuration change, not a code change, which is the
 * whole point of putting the capability behind a port.
 *
 * ## Why it does NOT fall back to the simulator
 *
 * A configured `visa` binding that cannot answer returns Visa's own failure — it does not quietly
 * produce a simulated one. A fallback would make an unconfigured or broken vendor integration
 * indistinguishable from a working one at every call site, in every log line and in every stored
 * row, which is the exact failure this platform has met before with a skipped delivery counted as
 * a success. The configured binding answers or says why it did not.
 *
 * The one exception is an unrecognised binding name, which is a deployment mistake rather than a
 * network condition: it returns [SchemeFailure.NOT_BOUND] naming the value it did not recognise,
 * so the message says what to fix.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class RoutedBinLookupPort(
    private val simulator: SimulatedSchemeAdapter,
    private val visa: VisaBinLookupAdapter,
    private val mastercard: MastercardBinLookupAdapter,
    @ConfigProperty(name = "openbank.card-processing.scheme.bin-lookup", defaultValue = "simulator")
    private val binding: String,
) : BinLookupPort {

    private val log = Logger.getLogger(RoutedBinLookupPort::class.java)

    override suspend fun lookup(bin: String): SchemeResult<BinAttributes> = when (binding.lowercase()) {
        BINDING_SIMULATOR -> simulator.lookup(bin)
        BINDING_VISA -> visa.lookup(bin)
        BINDING_MASTERCARD -> mastercard.lookup(bin)
        else -> {
            log.warnf(
                "unknown BIN lookup binding %s — expected one of %s/%s/%s",
                binding,
                BINDING_SIMULATOR,
                BINDING_VISA,
                BINDING_MASTERCARD,
            )
            SchemeResult.Unanswered(
                SchemeFailure.NOT_BOUND,
                CardScheme.SIMULATOR,
                "openbank.card-processing.scheme.bin-lookup=$binding names no adapter",
            )
        }
    }

    private companion object {
        const val BINDING_SIMULATOR = "simulator"
        const val BINDING_VISA = "visa"
        const val BINDING_MASTERCARD = "mastercard"
    }
}
