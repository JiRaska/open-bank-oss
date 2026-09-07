// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.scheme

import com.openbank.cardprocessing.infrastructure.scheme.SimulatedSchemeAdapter
import com.openbank.libs.domain.cards.scheme.BinAttributes
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.FundingSource
import com.openbank.libs.domain.cards.scheme.MerchantDescriptor
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The simulator's two load-bearing properties: it says it is the simulator, and it refuses to
 * answer what it does not know.
 */
class SimulatedSchemeAdapterTest {

    private val adapter = SimulatedSchemeAdapter()

    @Test
    fun `a known test range resolves and is stamped SIMULATOR`(): Unit = runBlocking {
        val result = adapter.lookup("411111")

        val answered = result as SchemeResult.Answered
        assertThat(answered.value.brand).isEqualTo("VISA")
        assertThat(answered.value.fundingSource).isEqualTo(FundingSource.DEBIT)
        // The provenance is the point: a stored answer records which binding produced it, so a
        // simulated BIN can never be read later as a Visa one.
        assertThat(answered.scheme).isEqualTo(CardScheme.SIMULATOR)
    }

    @Test
    fun `an unknown BIN is NOT_FOUND, never an invented issuer`(): Unit = runBlocking {
        val result = adapter.lookup("999999")

        // Read BEFORE narrowing: `Unanswered` is `SchemeResult<Nothing>`, so once Kotlin has
        // smart-cast to it `valueOrNull()` is `Nothing?` and AssertJ has no overload for that.
        val value: BinAttributes? = result.valueOrNull()
        assertThat((result as SchemeResult.Unanswered).failure).isEqualTo(SchemeFailure.NOT_FOUND)
        // A simulator that answers everything teaches its callers that the lookup always succeeds,
        // and the branch handling a miss then ships untested.
        assertThat(value).isNull()
    }

    @Test
    fun `a BIN of the wrong shape is MALFORMED, which is a different fact from NOT_FOUND`(): Unit = runBlocking {
        val tooShort = adapter.lookup("4111") as SchemeResult.Unanswered
        val notDigits = adapter.lookup("41111x") as SchemeResult.Unanswered

        assertThat(tooShort.failure).isEqualTo(SchemeFailure.MALFORMED)
        assertThat(notDigits.failure).isEqualTo(SchemeFailure.MALFORMED)
        // MALFORMED says the caller sent nonsense; NOT_FOUND says the network has no such range.
        // Merging them would hide a client bug behind a plausible business answer.
        assertThat(tooShort.failure).isNotEqualTo(SchemeFailure.NOT_FOUND)
    }

    @Test
    fun `an acquirer descriptor is cleaned, not resolved`(): Unit = runBlocking {
        val result = adapter.identify(
            MerchantDescriptor(descriptor = "SQ *COFFEE HOUSE 004821", mcc = "5812", countryCode = "CZ"),
        ) as SchemeResult.Answered

        assertThat(result.value.name).isEqualTo("Coffee House")
        assertThat(result.value.mcc).isEqualTo("5812")
        // No directory, so no id and no website. Synthesising either would be stored by a caller
        // and later read as a real network reference.
        assertThat(result.value.networkMerchantId).isNull()
        assertThat(result.value.website).isNull()
    }

    @Test
    fun `an empty descriptor is refused rather than echoed`(): Unit = runBlocking {
        val result = adapter.identify(MerchantDescriptor(descriptor = "   ", mcc = null, countryCode = null))

        assertThat((result as SchemeResult.Unanswered).failure).isEqualTo(SchemeFailure.MALFORMED)
    }
}
