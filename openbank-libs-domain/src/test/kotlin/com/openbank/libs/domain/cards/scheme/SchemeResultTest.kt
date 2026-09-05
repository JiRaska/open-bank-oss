// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.cards.scheme

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The result type's two properties that matter, and one that a reader might assume and should not.
 *
 * These are small tests for a small type, and they exist because the type's whole job is to keep
 * two things apart that a boolean or a nullable would merge: an answer from a network, and a
 * failure with a reason a caller branches on.
 */
class SchemeResultTest {

    private val attributes = BinAttributes(
        bin = "411111",
        brand = "VISA",
        productType = "CLASSIC",
        fundingSource = FundingSource.DEBIT,
        issuerName = "Simulated Issuer",
        issuerCountry = "CZ",
    )

    @Test
    fun `an answer carries the scheme that produced it`() {
        val result: SchemeResult<BinAttributes> = SchemeResult.Answered(attributes, CardScheme.SIMULATOR)

        assertThat(result.valueOrNull()).isEqualTo(attributes)
        assertThat((result as SchemeResult.Answered).scheme).isEqualTo(CardScheme.SIMULATOR)
    }

    @Test
    fun `an unanswered call yields no value and keeps its reason`() {
        val result: SchemeResult<BinAttributes> =
            SchemeResult.Unanswered(SchemeFailure.NOT_BOUND, CardScheme.VISA, "adapter not configured")

        assertThat(result.valueOrNull()).isNull()
        assertThat(result.failure).isEqualTo(SchemeFailure.NOT_BOUND)
        // NOT_BOUND and UNAVAILABLE must never be folded together: one is permanent until someone
        // configures an adapter, the other clears on its own. A caller that cannot tell them apart
        // retries the first for ever and gives up on the second.
        assertThat(result.failure).isNotEqualTo(SchemeFailure.UNAVAILABLE)
    }

    @Test
    fun `SIMULATOR is a scheme value, so a simulated answer is never indistinguishable from a real one`() {
        val simulated: SchemeResult<BinAttributes> = SchemeResult.Answered(attributes, CardScheme.SIMULATOR)
        val real: SchemeResult<BinAttributes> = SchemeResult.Answered(attributes, CardScheme.VISA)

        // Same value, different provenance — and the provenance is on the result, so a caller
        // logging or storing an answer records which one it was. This is the same discipline as
        // giving a skipped delivery its own outcome rather than sharing one with success.
        assertThat(simulated.valueOrNull()).isEqualTo(real.valueOrNull())
        assertThat(simulated).isNotEqualTo(real)
    }
}
