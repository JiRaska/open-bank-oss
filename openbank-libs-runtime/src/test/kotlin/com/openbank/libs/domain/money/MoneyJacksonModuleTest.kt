// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyJacksonModuleTest {

    private val canonical: ObjectMapper = jacksonObjectMapper().registerModule(MoneyJacksonModule())
    private val legacy: ObjectMapper =
        jacksonObjectMapper().registerModule(MoneyJacksonModule(MoneyWireShape.LEGACY_OBJECT))

    data class Carrier(val price: Money, val settlement: CurrencyCode, val fee: Money? = null)

    @Nested
    inner class CanonicalShape {

        @Test
        fun `writes the amount as decimal text and the currency as its code, and nothing else`() {
            // Literals, not values derived from the type: a test that built its expectation from
            // Money.toString would move together with a regression.
            assertThat(
                canonical.writeValueAsString(Money.of("10", "EUR")),
            ).isEqualTo("""{"amount":"10.00","currency":"EUR"}""")
            assertThat(
                canonical.writeValueAsString(Money.of("-5", "JPY")),
            ).isEqualTo("""{"amount":"-5","currency":"JPY"}""")
            assertThat(canonical.writeValueAsString(Money.of("1E+3", "KWD")))
                .isEqualTo("""{"amount":"1000.000","currency":"KWD"}""")
            assertThat(canonical.writeValueAsString(CurrencyCode.CZK)).isEqualTo("\"CZK\"")
        }

        @Test
        fun `round-trips across zero, two and three digit currencies and mixed input scales`() {
            val inputs = listOf("0", "1", "-1", "0.5", "12.3", "1E+3", "999999999999", "-0.0")
            for (code in listOf("JPY", "EUR", "KWD")) {
                val digits = CurrencyCode.of(code).defaultFractionDigits
                for (text in inputs) {
                    val raw = BigDecimal(text)
                    if (raw.stripTrailingZeros().scale() > digits) continue
                    val money = Money.of(raw, code)
                    val json = canonical.writeValueAsString(money)
                    val back = canonical.readValue<Money>(json)
                    assertThat(back).describedAs("$text $code via $json").isEqualTo(money)
                    assertThat(back.hashCode()).isEqualTo(money.hashCode())
                    assertThat(canonical.writeValueAsString(back)).isEqualTo(json)
                }
            }
        }

        @Test
        fun `round-trips inside another type, null included`() {
            val carrier = Carrier(Money.of("19.99", "EUR"), CurrencyCode.CHF)
            val json = canonical.writeValueAsString(carrier)
            assertThat(json)
                .isEqualTo("""{"price":{"amount":"19.99","currency":"EUR"},"settlement":"CHF","fee":null}""")
            assertThat(canonical.readValue<Carrier>(json)).isEqualTo(carrier)
        }

        @Test
        fun `the module is discoverable, so findAndRegisterModules is enough`() {
            val discovered = jacksonObjectMapper().findAndRegisterModules()
            val money = Money.of("7.50", "EUR")
            assertThat(discovered.readValue<Money>(discovered.writeValueAsString(money))).isEqualTo(money)
        }
    }

    @Nested
    inner class TolerantRead {

        @Test
        fun `reads every shape a producer has written - text or number, code or object, either mode`() {
            val expected = Money.of("10.00", "EUR")
            listOf(
                """{"amount":"10.00","currency":"EUR"}""",
                """{"amount":10.00,"currency":"EUR"}""",
                """{"amount":10,"currency":"eur"}""",
                """{"amount":"10","currency":{"code":"EUR"}}""",
                """{"currency":{"defaultFractionDigits":2,"code":"EUR"},"amount":1E+1}""",
                """{"amount":10.00,"currency":{"code":"EUR","defaultFractionDigits":2},"isNonNegative":true,"isZero":false,"isNegative":false,"isPositive":true}""",
            ).forEach { json ->
                assertThat(canonical.readValue<Money>(json)).describedAs(json).isEqualTo(expected)
                assertThat(legacy.readValue<Money>(json)).describedAs(json).isEqualTo(expected)
            }
        }

        @Test
        fun `a numeric amount is read from its text, not through a double`() {
            // 0.1 + 0.2 style drift and 17-significant-digit truncation both come from binary
            // floating point; neither can appear when the token text is what gets parsed.
            val json = """{"amount":1234567890123456789.12,"currency":"EUR"}"""
            assertThat(canonical.readValue<Money>(json).amount.toPlainString()).isEqualTo("1234567890123456789.12")
        }

        @Test
        fun `a standalone currency reads from a code or an object`() {
            assertThat(canonical.readValue<CurrencyCode>("\"czk\"")).isEqualTo(CurrencyCode.CZK)
            assertThat(
                canonical.readValue<CurrencyCode>("""{"code":"CZK","defaultFractionDigits":2}"""),
            ).isEqualTo(CurrencyCode.CZK)
        }
    }

    @Nested
    inner class LegacyShape {

        @Test
        fun `writes exactly what bean introspection wrote before the module existed`() {
            // Captured from the unmodified data class with a vanilla jacksonObjectMapper().
            assertThat(legacy.writeValueAsString(Money.of("10.00", "EUR"))).isEqualTo(
                """{"amount":10.00,"currency":{"code":"EUR","defaultFractionDigits":2},"isNonNegative":true,"isZero":false,"isNegative":false,"isPositive":true}""",
            )
            assertThat(legacy.writeValueAsString(Money.of("-5", "JPY"))).isEqualTo(
                """{"amount":-5,"currency":{"code":"JPY","defaultFractionDigits":0},"isNonNegative":false,"isZero":false,"isNegative":true,"isPositive":false}""",
            )
            assertThat(
                legacy.writeValueAsString(CurrencyCode.CZK),
            ).isEqualTo("""{"code":"CZK","defaultFractionDigits":2}""")
        }

        @Test
        fun `what one mode writes the other reads`() {
            val money = Money.of("1234.50", "CZK")
            assertThat(canonical.readValue<Money>(legacy.writeValueAsString(money))).isEqualTo(money)
            assertThat(legacy.readValue<Money>(canonical.writeValueAsString(money))).isEqualTo(money)
        }
    }

    @Nested
    inner class Rejected {

        private fun rejects(json: String, fragment: String) {
            assertThatThrownBy { canonical.readValue<Money>(json) }
                .describedAs(json)
                .isInstanceOf(JsonMappingException::class.java)
                .hasMessageContaining(fragment)
        }

        @Test
        fun `an out-of-range amount is rejected as text and as a number`() {
            rejects("""{"amount":"1E+2000000000","currency":"EUR"}""", "integer digits")
            rejects("""{"amount":1E+2000000000,"currency":"EUR"}""", "integer digits")
            rejects("""{"amount":1e1000000000,"currency":"EUR"}""", "integer digits")
            rejects("""{"amount":"1E-2000000000","currency":"EUR"}""", "scale")
            rejects("""{"amount":1E-2000000000,"currency":"EUR"}""", "scale")
            rejects("""{"amount":"${"9".repeat(200)}","currency":"EUR"}""", "characters")
            rejects("""{"amount":${"9".repeat(200)},"currency":"EUR"}""", "characters")
        }

        @Test
        fun `an amount finer than the currency is rejected, not rounded`() {
            rejects("""{"amount":"1.005","currency":"EUR"}""", "exceeds currency EUR fraction digits")
            rejects("""{"amount":1.5,"currency":"JPY"}""", "exceeds currency JPY fraction digits")
        }

        @Test
        fun `malformed input is a mapping error, never an unchecked exception`() {
            rejects("""{"amount":"ten","currency":"EUR"}""", "not a decimal number")
            rejects("""{"amount":"NaN","currency":"EUR"}""", "not a decimal number")
            rejects("""{"amount":true,"currency":"EUR"}""", "must be a decimal string or a number")
            rejects("""{"amount":{"value":1},"currency":"EUR"}""", "must be a decimal string or a number")
            rejects("""{"amount":"1.00"}""", "both required")
            rejects("""{"currency":"EUR"}""", "both required")
            rejects("""{"amount":"1.00","currency":null}""", "currency must be a code")
            rejects("""{"amount":"1.00","currency":{"name":"EUR"}}""", "currency must be a code")
            rejects("""{"amount":"1.00","currency":"QQQ"}""", "Unknown ISO 4217 currency code: QQQ")
            rejects("""{"amount":"1.00","currency":"EURO"}""", "must be 3 letters")
            rejects("""{"amount":"1.00","currency":"XAU"}""", "has no minor unit")
            rejects(""""10.00 EUR"""", "expected an object")
            rejects("""[10,"EUR"]""", "expected an object")
        }
    }

    @Nested
    inner class WithoutTheModule {

        @Test
        fun `a mapper that never registered the module cannot read Money - the omission is loud on the read side`() {
            assertThatThrownBy { jacksonObjectMapper().readValue<Money>("""{"amount":"1.00","currency":"EUR"}""") }
                .isInstanceOf(JsonMappingException::class.java)
        }
    }
}

class MoneyFingerprintStabilityTest {

    private data class Request(val requestedAmount: Money)

    @Test
    fun `a request fingerprinted before canonicalisation hashes equal to the same request after it`() {
        // What the pre-canonical data class wrote for an amount sent as 10000 (scale 0), captured
        // with a vanilla jacksonObjectMapper(): a service keeping LEGACY_OBJECT sees idempotency
        // keys stored before this change match the canonical 10000.00 after it. Two layers make
        // it so: the tree is built by Jackson's default (non-exact) JsonNodeFactory, which strips
        // trailing zeros from decimals, and RequestFingerprints.normalise strips them again.
        val before = """{"requestedAmount":{"amount":10000,"currency":{"code":"EUR","defaultFractionDigits":2},""" +
            """"isNonNegative":true,"isZero":false,"isNegative":false,"isPositive":true}}"""
        val legacy = jacksonObjectMapper().registerModule(MoneyJacksonModule(MoneyWireShape.LEGACY_OBJECT))
        val after = Request(Money.of("10000", "EUR"))
        val fp = { dto: Any -> com.openbank.libs.idempotency.RequestFingerprints.of(legacy, "POST", "/x", dto) }
        assertThat(fp(after)).isEqualTo(fp(before))
        assertThat(fp(Request(Money.of("1E+4", "EUR")))).isEqualTo(fp(before))
        // ...and it is still a fingerprint of the value: a different amount is a different request.
        assertThat(fp(Request(Money.of("10000.01", "EUR")))).isNotEqualTo(fp(before))
    }
}
