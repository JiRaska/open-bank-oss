// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.domain

import com.openbank.vop.domain.model.VopNoDataReason
import com.openbank.vop.domain.model.VopOutcome
import com.openbank.vop.domain.model.VopVerification
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The name-disclosure invariant (ADR-0171 §5) is the whole defence against VoP becoming an
 * account-holder-name oracle, so it is enforced in the type, not left to callers to remember.
 */
class VopVerificationInvariantTest {

    private val at: Instant = Instant.parse("2026-07-16T10:00:00Z")

    @Test
    fun `CLOSE_MATCH may disclose the matched name`() {
        assertThatCode {
            VopVerification(outcome = VopOutcome.CLOSE_MATCH, matchedName = "Jiří Raška", verifiedAt = at)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `NO_MATCH must never disclose a name`() {
        assertThatThrownBy {
            VopVerification(outcome = VopOutcome.NO_MATCH, matchedName = "Jiří Raška", verifiedAt = at)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("CLOSE_MATCH")
    }

    @Test
    fun `MATCH does not echo a name either — the payer already supplied it`() {
        assertThatThrownBy {
            VopVerification(outcome = VopOutcome.MATCH, matchedName = "Jiří Raška", verifiedAt = at)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `NO_DATA requires a reason`() {
        assertThatThrownBy {
            VopVerification(outcome = VopOutcome.NO_DATA, verifiedAt = at)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("noDataReason")

        assertThat(
            VopVerification(
                outcome = VopOutcome.NO_DATA,
                noDataReason = VopNoDataReason.NO_SCHEME_CONNECTIVITY,
                verifiedAt = at,
            ).noDataReason,
        ).isEqualTo(VopNoDataReason.NO_SCHEME_CONNECTIVITY)
    }

    @Test
    fun `a non-NO_DATA outcome must not carry a reason`() {
        assertThatThrownBy {
            VopVerification(
                outcome = VopOutcome.MATCH,
                noDataReason = VopNoDataReason.LOOKUP_UNAVAILABLE,
                verifiedAt = at,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
