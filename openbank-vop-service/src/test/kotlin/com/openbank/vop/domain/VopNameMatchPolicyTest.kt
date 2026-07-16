// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.domain

import com.openbank.vop.domain.match.VopNameMatchPolicy
import com.openbank.vop.domain.model.VopOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class VopNameMatchPolicyTest {

    private val policy = VopNameMatchPolicy()

    @ParameterizedTest(name = "MATCH: \"{0}\" vs \"{1}\"")
    @CsvSource(
        // Identical.
        "Jiří Raška,Jiří Raška",
        // Diacritics are presentation, not identity — a payer typing without a Czech keyboard is
        // not making a mistake.
        "Jiri Raska,Jiří Raška",
        // Case and padding.
        "JIŘÍ RAŠKA,jiří raška",
        "'  Jiří   Raška  ',Jiří Raška",
        // Legal form differs only.
        "Acme s.r.o.,Acme",
        "Acme,Acme s.r.o.",
        "Acme a.s.,Acme",
        "Acme Ltd,Acme",
    )
    fun `same name is MATCH`(supplied: String, actual: String) {
        assertThat(policy.match(supplied, actual)).isEqualTo(VopOutcome.MATCH)
    }

    @ParameterizedTest(name = "CLOSE_MATCH: \"{0}\" vs \"{1}\"")
    @CsvSource(
        // Token order — our field order is not every PSP's field order.
        "Raška Jiří,Jiří Raška",
        // An initial for a given name.
        "J. Raška,Jiří Raška",
        "J Raska,Jiří Raška",
        // A middle name one side carries and the other omits.
        "Jiří Raška,Jiří Jan Raška",
        "Jiří Jan Raška,Jiří Raška",
        // One character wrong — a typo, not a different person.
        "Jiri Raskb,Jiří Raška",
    )
    fun `near miss is CLOSE_MATCH`(supplied: String, actual: String) {
        assertThat(policy.match(supplied, actual)).isEqualTo(VopOutcome.CLOSE_MATCH)
    }

    @ParameterizedTest(name = "NO_MATCH: \"{0}\" vs \"{1}\"")
    @CsvSource(
        "Petr Novák,Jiří Raška",
        "Jan Kovář,Jiří Raška",
        // Same family name, different person — the given name is not a detail to wave through.
        "Petr Raška,Jiří Raška",
        "Acme s.r.o.,Globex s.r.o.",
        // Initials alone are far too weak to call a near-miss of a payee name: "J. K." must not
        // pass for "Jan Kovář" just because the initials line up.
        "J. K.,Jan Kovář",
    )
    fun `different name is NO_MATCH`(supplied: String, actual: String) {
        assertThat(policy.match(supplied, actual)).isEqualTo(VopOutcome.NO_MATCH)
    }

    @Test
    fun `matching is symmetric`() {
        val pairs = listOf(
            "Jiří Raška" to "Raška Jiří",
            "J. Raška" to "Jiří Raška",
            "Petr Novák" to "Jiří Raška",
            "Acme s.r.o." to "Acme",
        )
        pairs.forEach { (a, b) ->
            assertThat(policy.match(a, b))
                .describedAs("match(%s, %s) must equal match(%s, %s)", a, b, b, a)
                .isEqualTo(policy.match(b, a))
        }
    }

    @Test
    fun `a blank name is a NO_DATA case for the caller, never a NO_MATCH`() {
        // Answering NO_MATCH for a name we simply do not hold would tell the payer their payee is
        // wrong when we never actually checked. The lookup owns that distinction.
        assertThatThrownBy { policy.match("Jiří Raška", "   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("NO_DATA")
    }

    @Test
    fun `a company genuinely named after a legal form keeps its leading token`() {
        // Two guards must hold together here. Only a *trailing* legal form is stripped, so
        // "SRO Praha" is not reduced to "Praha" — and the dropped-token rule refuses to fire when
        // only one token would corroborate the match, so "SRO Praha" does not near-miss "Praha"
        // on their single shared token either. These are two different payees.
        assertThat(policy.match("SRO Praha", "Praha")).isEqualTo(VopOutcome.NO_MATCH)
    }

    @Test
    fun `a dropped token needs at least two tokens left to corroborate it`() {
        // The dropped-token rule exists for the middle-name case, where the remaining tokens still
        // pin the identity. Applied to a one-token name it degenerates into "shares any token",
        // which is two different payees, not a near-miss.
        assertThat(policy.match("Acme Praha", "Praha")).isEqualTo(VopOutcome.NO_MATCH)
        assertThat(policy.match("Jiří Raška", "Raška")).isEqualTo(VopOutcome.NO_MATCH)
        // …but with two corroborating tokens it is a genuine near-miss.
        assertThat(policy.match("Jiří Jan Raška", "Jiří Raška")).isEqualTo(VopOutcome.CLOSE_MATCH)
    }

    @Test
    fun `edit distance is configurable and zero means typo-intolerant`() {
        val strict = VopNameMatchPolicy(maxEditDistance = 0)
        assertThat(strict.match("Jiri Raskb", "Jiří Raška")).isEqualTo(VopOutcome.NO_MATCH)
        // Reordering and initials are name-shape tolerance, not typo tolerance — still CLOSE_MATCH.
        assertThat(strict.match("Raška Jiří", "Jiří Raška")).isEqualTo(VopOutcome.CLOSE_MATCH)
    }

    @Test
    fun `a negative edit distance is rejected at construction`() {
        assertThatThrownBy { VopNameMatchPolicy(maxEditDistance = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
