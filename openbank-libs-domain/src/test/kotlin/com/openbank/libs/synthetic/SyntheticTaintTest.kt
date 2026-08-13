// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.synthetic

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0252 phase 1. These assertions are about the DEFAULT, not about parsing: the parse is four
 * lines and could not plausibly be wrong, while the direction it fails in decides whether a
 * regulatory return can silently omit real customer money.
 */
class SyntheticTaintTest {

    @Test
    fun `only an exact true means synthetic`() {
        assertThat(SyntheticTaint.isTainted("true")).isTrue()
        assertThat(SyntheticTaint.isTainted("TRUE")).isTrue()
        assertThat(SyntheticTaint.isTainted("  true  ")).isTrue()
    }

    @Test
    fun `everything else is REAL, including the values a permissive parser would accept`() {
        // The dangerous direction is real-read-as-synthetic: that drops real money out of
        // FINREP/COREP/AnaCredit and the AML baseline, silently and without bound. So a stray
        // value in a header must never be able to suppress a record.
        for (value in listOf(null, "", "   ", "1", "yes", "on", "TRUE!", "synthetic", "false", "0")) {
            assertThat(SyntheticTaint.isTainted(value))
                .withFailMessage("%s must be read as REAL, not synthetic", value)
                .isFalse()
        }
    }

    @Test
    fun `header lookup is case-insensitive because casing does not survive every hop`() {
        // Kafka preserves header casing, HTTP/2 lower-cases it, and a bridge may do either. A
        // case-sensitive lookup would drop the taint at exactly one hop and nowhere else.
        assertThat(SyntheticTaint.isTainted(mapOf("X-OpenBank-Synthetic" to "true"))).isTrue()
        assertThat(SyntheticTaint.isTainted(mapOf("x-openbank-synthetic" to "true"))).isTrue()
        assertThat(SyntheticTaint.isTainted(mapOf("X-OPENBANK-SYNTHETIC" to "true"))).isTrue()
    }

    @Test
    fun `an absent or differently-named header is REAL`() {
        assertThat(SyntheticTaint.isTainted(emptyMap())).isFalse()
        assertThat(SyntheticTaint.isTainted(mapOf("x-openbank-synthetics" to "true"))).isFalse()
        assertThat(SyntheticTaint.isTainted(mapOf("x-openbank-synthetic" to null))).isFalse()
    }

    @Test
    fun `regulatory aggregates admit real activity and exclude synthetic`() {
        assertThat(SyntheticTaint.admittedToRegulatoryAggregate(tainted = false)).isTrue()
        assertThat(SyntheticTaint.admittedToRegulatoryAggregate(tainted = true)).isFalse()
        assertThat(SyntheticTaint.admittedToRegulatoryAggregate(mapOf(SyntheticTaint.KAFKA_HEADER to "true")))
            .isFalse()
        assertThat(SyntheticTaint.admittedToRegulatoryAggregate(emptyMap())).isTrue()
    }

    @Test
    fun `the stamped value round-trips`() {
        assertThat(SyntheticTaint.isTainted(SyntheticTaint.headerValue())).isTrue()
    }

    @Test
    fun `there is no API for bypassing a control on synthetic traffic`() {
        // Not a tautology test: it pins the API SURFACE. Controls (screening, VoP, SCA, limits)
        // must run for synthetic traffic — that is what the canary exists to prove — so the
        // absence of a `mayBypassControl`-shaped helper is a deliberate design property, and a
        // future addition should have to delete this assertion and explain itself.
        val members = SyntheticTaint::class.java.methods.map { it.name }
        assertThat(members).noneMatch { it.contains("bypass", ignoreCase = true) }
        assertThat(members).noneMatch { it.contains("skip", ignoreCase = true) }
    }
}
