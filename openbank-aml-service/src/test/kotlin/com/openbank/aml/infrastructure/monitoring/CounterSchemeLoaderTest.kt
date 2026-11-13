// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.domain.monitoring.CounterScheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.Instant

class CounterSchemeLoaderTest {
    private val mapper = ObjectMapper()
    private val shipped = File("src/main/resources/aml/monitoring-counter-scheme.synthetic.json").readText()

    private fun scheme(json: String = shipped): CounterScheme = CounterSchemeLoader.parse(mapper, json)

    @Test
    fun `the shipped scheme parses and declares itself synthetic`() {
        val scheme = scheme()
        assertThat(scheme.id).endsWith(".synthetic")
        assertThat(scheme.revision).isEqualTo(1)
        assertThat(scheme.contentHash).matches("[0-9a-f]{64}")
        assertThat(scheme.bands.map { it.code }).allMatch { it.startsWith("SYN_") }
    }

    @Test
    fun `a band is lower-inclusive, upper-exclusive and bound to one currency`() {
        val scheme = scheme()
        assertThat(scheme.bandCodesFor("CZK", BigDecimal("900.00"))).containsExactly("ALL", "SYN_CZK_BELOW_MARK")
        assertThat(scheme.bandCodesFor("CZK", BigDecimal("999.99"))).containsExactly("ALL", "SYN_CZK_BELOW_MARK")
        assertThat(scheme.bandCodesFor("CZK", BigDecimal("1000.00"))).containsExactly("ALL")
        assertThat(scheme.bandCodesFor("CZK", BigDecimal("899.99"))).containsExactly("ALL")
        // 950 EUR is inside the CZK band's numbers and must not count there: no cross-currency band.
        assertThat(scheme.bandCodesFor("EUR", BigDecimal("950.00"))).containsExactly("ALL")
        assertThat(scheme.bandCodesFor("EUR", BigDecimal("95.00"))).containsExactly("ALL", "SYN_EUR_BELOW_MARK")
    }

    @Test
    fun `buckets are aligned to the epoch by the scheme's own width`() {
        val scheme = scheme()
        assertThat(
            scheme.bucketStart(Instant.parse("2026-03-01T10:59:59Z")),
        ).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"))
        assertThat(
            scheme.bucketStart(Instant.parse("2026-03-01T11:00:00Z")),
        ).isEqualTo(Instant.parse("2026-03-01T11:00:00Z"))
    }

    @Test
    fun `an unknown field, a numeric amount or an unordered band is rejected, never defaulted`() {
        assertThatThrownBy { scheme(shipped.replace("\"purpose\"", "\"porpoise\"")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { scheme(shipped.replace("\"900.00\"", "900.00")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { scheme(shipped.replace("\"900.00\"", "\"1000.00\"")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { scheme(shipped.replace("SYN_EUR_BELOW_MARK", "SYN_CZK_BELOW_MARK")) }
            .describedAs("duplicate band code").isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { scheme(shipped.replace("3600", "7000")) }
            .describedAs("a bucket that does not divide a day").isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { scheme(shipped.replace("SYN_EUR_BELOW_MARK", "ALL")) }
            .describedAs("ALL is reserved").isInstanceOf(IllegalArgumentException::class.java)
    }
}
