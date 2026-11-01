// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.reference

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductTypePayloadTest {
    private val ok = ProductTypePayload(
        code = "MM_DEPOSIT",
        permitted = true,
        maxPrincipal = mapOf("CZK" to BigDecimal("1000000"), "EUR" to BigDecimal("50000")),
        maxTenorDays = 365,
        allowedCurrencies = listOf("EUR", "CZK"),
    )

    @Test
    fun `a well-formed product type validates and keys by its code`() {
        assertThatCode { ok.validate() }.doesNotThrowAnyException()
        assertThat(ok.key()).isEqualTo("MM_DEPOSIT")
        assertThat(ok.recordKind).isEqualTo(ReferenceKind.PRODUCT_TYPE)
        assertThatCode { ok.copy(maxTenorDays = null, allowedCurrencies = null).validate() }.doesNotThrowAnyException()
        assertThatCode { ok.copy(maxTenorDays = 1).validate() }.doesNotThrowAnyException()
        assertThatCode { ProductTypePayload("BANNED", permitted = false).validate() }.doesNotThrowAnyException()
    }

    @Test
    fun `each mandate rule refuses its broken case`() {
        assertThatThrownBy { ok.copy(code = "mm").validate() }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ok.copy(maxPrincipal = mapOf("czk" to BigDecimal.ONE), allowedCurrencies = null).validate()
        }.hasMessageContaining("ISO 4217")
        assertThatThrownBy {
            ok.copy(maxPrincipal = mapOf("CZK" to BigDecimal.ZERO), allowedCurrencies = null).validate()
        }.hasMessage("maxPrincipal in CZK must be positive")
        assertThatThrownBy { ok.copy(maxTenorDays = 0).validate() }.hasMessage("maxTenorDays must be positive")
        assertThatThrownBy { ok.copy(allowedCurrencies = listOf("CZK")).validate() }
            .hasMessage("allowedCurrencies must equal the maxPrincipal currencies")
        assertThatThrownBy { ProductTypePayload("OPEN", permitted = true).validate() }
            .hasMessage("a permitted product names at least one currency")
    }

    @Test
    fun `a generic record keys by code then id and requires a name`() {
        val g = GenericPayload(ReferenceKind.COUNTRY, mapOf("code" to "CZ", "id" to "X", "name" to "Czechia"))
        assertThat(g.key()).isEqualTo("CZ")
        assertThatCode { g.validate() }.doesNotThrowAnyException()
        assertThat(GenericPayload(ReferenceKind.COUNTRY, mapOf("id" to "ID1", "name" to "n")).key()).isEqualTo("ID1")
        assertThat(GenericPayload(ReferenceKind.COUNTRY, mapOf("name" to "n")).key()).isEqualTo("")
        assertThatThrownBy { GenericPayload(ReferenceKind.COUNTRY, mapOf("name" to "n")).validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { GenericPayload(ReferenceKind.COUNTRY, mapOf("code" to "CZ")).validate() }
            .hasMessageContaining("name must be")
        assertThatThrownBy {
            GenericPayload(ReferenceKind.PRODUCT_TYPE, mapOf("code" to "X", "name" to "n")).validate()
        }.hasMessage("product-type has a typed payload")
        val wide = mapOf("code" to "X", "name" to "n") + (1..62).associate { "f$it" to it }
        assertThatCode { GenericPayload(ReferenceKind.COUNTRY, wide).validate() }.doesNotThrowAnyException()
        assertThatThrownBy { GenericPayload(ReferenceKind.COUNTRY, wide + ("f63" to 63)).validate() }
            .hasMessage("a record has at most 64 fields")
    }
}
