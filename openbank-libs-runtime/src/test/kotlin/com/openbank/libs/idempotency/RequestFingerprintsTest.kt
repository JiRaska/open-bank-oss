// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RequestFingerprintsTest {

    private val mapper = jacksonObjectMapper()

    data class Payment(val amount: BigDecimal, val currency: String, val meta: Map<String, Any?>? = null)

    @Test
    fun `property and map-key order do not matter`() {
        val a = RequestFingerprints.canonical(mapper, """{"b":1,"a":{"y":2,"x":1}}""")
        val b = RequestFingerprints.canonical(mapper, """{"a":{"x":1,"y":2},"b":1}""")
        assertThat(a).isEqualTo(b).isEqualTo("""{"a":{"x":1,"y":2},"b":1}""")
        assertThat(RequestFingerprints.canonical(mapper, Payment(BigDecimal.ONE, "CZK", mapOf("z" to 1, "a" to 2))))
            .isEqualTo("""{"amount":1,"currency":"CZK","meta":{"a":2,"z":1}}""")
    }

    @Test
    fun `whitespace does not matter`() {
        assertThat(RequestFingerprints.canonical(mapper, "{ \"a\" :\n 1 }"))
            .isEqualTo(RequestFingerprints.canonical(mapper, """{"a":1}"""))
    }

    @Test
    fun `BigDecimal scale does not matter - 1_0 equals 1`() {
        val plain = RequestFingerprints.canonical(mapper, Payment(BigDecimal("10"), "CZK"))
        assertThat(RequestFingerprints.canonical(mapper, Payment(BigDecimal("10.00"), "CZK"))).isEqualTo(plain)
        assertThat(RequestFingerprints.canonical(mapper, """{"amount":10.0,"currency":"CZK"}""")).isEqualTo(plain)
        assertThat(plain).isEqualTo("""{"amount":10,"currency":"CZK"}""")
        assertThat(RequestFingerprints.canonical(mapper, Payment(BigDecimal("10.01"), "CZK"))).isNotEqualTo(plain)
    }

    @Test
    fun `null field equals absent field`() {
        assertThat(RequestFingerprints.canonical(mapper, """{"a":1,"b":null}"""))
            .isEqualTo(RequestFingerprints.canonical(mapper, """{"a":1}"""))
        assertThat(RequestFingerprints.canonical(mapper, Payment(BigDecimal.ONE, "CZK", null)))
            .isEqualTo("""{"amount":1,"currency":"CZK"}""")
    }

    @Test
    fun `fingerprint combines method, path and canonical body`() {
        assertThat(RequestFingerprints.of(mapper, "POST", "/p", """{"b":1,"a":2}"""))
            .isEqualTo(RequestFingerprint.of("POST", "/p", """{"a":2,"b":1}"""))
    }
}
