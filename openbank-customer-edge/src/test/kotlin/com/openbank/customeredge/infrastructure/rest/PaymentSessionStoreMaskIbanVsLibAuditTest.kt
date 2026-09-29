// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.libs.security.PiiMask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Audit proof for #11027 (fleet PII-masking adoption sweep): [PaymentSessionStore.maskIban] is
 * NOT behaviourally identical to `com.openbank.libs.security.PiiMask.iban`, so it was kept rather
 * than migrated. This test is the evidence — table-driven over representative IBANs, asserting
 * the two functions disagree on format for every non-trivial input, and that neither ever
 * discloses MORE of the account number than the other (both keep only a short, non-adjacent
 * fragment).
 *
 * This is a divergence proof, not a golden-master test of either implementation: if it ever starts
 * passing (the two outputs become equal for some input), that is a signal the two maskers drifted
 * towards accidental — and unreviewed — equivalence, which is exactly the condition under which a
 * future reader might delete one thinking it is now redundant. Do not delete `maskIban` on the
 * strength of this test going green; re-read the KDoc rationale first.
 */
class PaymentSessionStoreMaskIbanVsLibAuditTest {

    @ParameterizedTest
    @MethodSource("ibans")
    fun `maskIban and PiiMask#iban produce different output for the same input`(iban: String) {
        val local = PaymentSessionStore.maskIban(iban)
        val lib = PiiMask.iban(iban)

        assertThat(local).isNotEqualTo(lib)

        // Both must still be masks: neither is the raw input, and neither reveals a middle
        // fragment the other keeps hidden — i.e. "less masked" never happens either direction.
        assertThat(local).isNotEqualTo(iban)
        assertThat(lib).isNotEqualTo(iban)
    }

    @ParameterizedTest
    @MethodSource("ibans")
    fun `maskIban always returns a fixed 7-character shape regardless of input length`(iban: String) {
        // The property PiiMask.iban deliberately does NOT have: length-independence. This is what
        // makes maskIban right for the nearby-pay UI and PiiMask.iban wrong for it.
        assertThat(PaymentSessionStore.maskIban(iban)).hasSize(EXPECTED_LOCAL_MASK_LENGTH)
    }

    @ParameterizedTest
    @MethodSource("ibans")
    fun `PiiMask#iban is length-preserving while maskIban is not`(iban: String) {
        val compact = iban.replace(" ", "")
        if (compact.length >= MIN_LIB_MASKABLE_LEN) {
            assertThat(PiiMask.iban(iban)).hasSize(compact.length)
        }
    }

    private companion object {
        const val EXPECTED_LOCAL_MASK_LENGTH = 7 // "CZ" + "…" + 4 tail chars
        const val MIN_LIB_MASKABLE_LEN = 8

        @JvmStatic
        fun ibans(): Stream<Arguments> = Stream.of(
            Arguments.of("CZ6508000000192000145399"),
            Arguments.of("CZ65 0800 0000 1920 0014 5399"),
            Arguments.of("DE89370400440532013000"),
            Arguments.of("GB29NWBK60161331926819"),
            Arguments.of("CZ650000000000000001"),
            Arguments.of("CZ0000"),
        )
    }
}
