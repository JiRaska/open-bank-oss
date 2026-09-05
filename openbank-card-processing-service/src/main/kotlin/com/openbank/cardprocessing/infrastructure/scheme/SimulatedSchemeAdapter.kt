// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.domain.cards.scheme.BinAttributes
import com.openbank.libs.domain.cards.scheme.BinLookupPort
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.FundingSource
import com.openbank.libs.domain.cards.scheme.MerchantDataPort
import com.openbank.libs.domain.cards.scheme.MerchantDescriptor
import com.openbank.libs.domain.cards.scheme.MerchantIdentity
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import jakarta.enterprise.context.ApplicationScoped

/**
 * The in-repo binding of the read-only scheme capabilities (ADR-0283 phase 2, #8810).
 *
 * ## Why a simulator is a first-class binding, not a test double
 *
 * The vendor adapters cannot run without developer-portal credentials, and a platform whose only
 * binding is one nobody can execute is a platform where the ports are never exercised — which is
 * how card-issuance's authorisation decision came to have no caller for a month. This binding is
 * always available, so every layer above it is testable, demonstrable and reviewable today.
 *
 * ## Every answer says it came from the simulator
 *
 * [SchemeResult.Answered.scheme] is [CardScheme.SIMULATOR] on every result. A caller that logs or
 * stores an answer records which binding produced it, so a simulated BIN can never be mistaken for
 * a Visa one afterwards. That is the same discipline as giving a skipped delivery its own outcome
 * value rather than sharing one with success.
 *
 * ## What it makes up, and what it refuses to
 *
 * The BIN table is deterministic and derived from the published **test** ranges the two networks
 * document for their sandboxes (411111 for Visa, 555555 for Mastercard). A BIN outside them is
 * [SchemeFailure.NOT_FOUND], NOT an invented issuer: a simulator that answers everything teaches
 * its callers that the lookup always succeeds, and the branch that handles a miss then ships
 * untested.
 */
@ApplicationScoped
class SimulatedSchemeAdapter : BinLookupPort, MerchantDataPort {

    override suspend fun lookup(bin: String): SchemeResult<BinAttributes> {
        val normalised = bin.trim()
        if (!normalised.matches(BIN_SHAPE)) {
            return SchemeResult.Unanswered(
                SchemeFailure.MALFORMED,
                CardScheme.SIMULATOR,
                "a BIN is 6 to 8 digits; got ${normalised.length} character(s)",
            )
        }
        val entry = TABLE.entries.firstOrNull { normalised.startsWith(it.key) }
            ?: return SchemeResult.Unanswered(
                SchemeFailure.NOT_FOUND,
                CardScheme.SIMULATOR,
                "no simulated issuer range covers $normalised",
            )
        return SchemeResult.Answered(entry.value.copy(bin = normalised), CardScheme.SIMULATOR)
    }

    /**
     * Turns an acquirer descriptor into a merchant, the way a network's merchant service would.
     *
     * The descriptor is cleaned rather than resolved: leading acquirer prefixes and trailing
     * store/terminal numbers are stripped, and the result is title-cased. This is honest about what
     * a simulator can do — it has no merchant directory, so it cannot tell you that `SQ *COFFEE`
     * is a particular café. Returning the cleaned string with the MCC the acquirer already sent is
     * useful and true; inventing a name and a website would not be.
     */
    override suspend fun identify(descriptor: MerchantDescriptor): SchemeResult<MerchantIdentity> {
        val raw = descriptor.descriptor.trim()
        if (raw.isEmpty()) {
            return SchemeResult.Unanswered(
                SchemeFailure.MALFORMED,
                CardScheme.SIMULATOR,
                "descriptor is empty",
            )
        }
        return SchemeResult.Answered(
            MerchantIdentity(
                name = cleanDescriptor(raw),
                mcc = descriptor.mcc,
                countryCode = descriptor.countryCode,
                city = null,
                website = null,
                // No directory, so no id. Null is the truthful answer; a synthesised id would be
                // stored by a caller and later read as a real network reference.
                networkMerchantId = null,
            ),
            CardScheme.SIMULATOR,
        )
    }

    private fun cleanDescriptor(raw: String): String {
        val withoutPrefix = raw.substringAfter('*', raw)
        val withoutTrailingDigits = withoutPrefix.trim().replace(TRAILING_REFERENCE, "")
        val words = withoutTrailingDigits.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return raw
        return words.joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private companion object {
        val BIN_SHAPE = Regex("^\\d{6,8}$")
        val TRAILING_REFERENCE = Regex("\\s+[#]?\\d{3,}$")
        val WHITESPACE = Regex("\\s+")

        /**
         * Keyed by the network's own published SANDBOX test range, so a developer using the same
         * card number against the real sandbox sees a comparable answer. Not an issuer directory
         * and not a claim about any real BIN.
         */
        val TABLE: Map<String, BinAttributes> = linkedMapOf(
            "411111" to BinAttributes(
                bin = "411111",
                brand = "VISA",
                productType = "CLASSIC",
                fundingSource = FundingSource.DEBIT,
                issuerName = "Simulated Issuer",
                issuerCountry = "CZ",
            ),
            "455555" to BinAttributes(
                bin = "455555",
                brand = "VISA",
                productType = "INFINITE",
                fundingSource = FundingSource.CREDIT,
                issuerName = "Simulated Issuer",
                issuerCountry = "CZ",
            ),
            "555555" to BinAttributes(
                bin = "555555",
                brand = "MASTERCARD",
                productType = "WORLD",
                fundingSource = FundingSource.CREDIT,
                issuerName = "Simulated Issuer",
                issuerCountry = "CZ",
            ),
            "522222" to BinAttributes(
                bin = "522222",
                brand = "MASTERCARD",
                productType = "STANDARD",
                fundingSource = FundingSource.PREPAID,
                issuerName = "Simulated Issuer",
                issuerCountry = "SK",
            ),
        )
    }
}
