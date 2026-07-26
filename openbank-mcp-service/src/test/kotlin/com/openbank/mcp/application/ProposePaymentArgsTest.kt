// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * T-T2 (#2414): `propose_payment`'s arguments are composed by a model and arrive over a protocol
 * whose client need not honour the advertised `inputSchema`. Before this, every field reached the
 * port unchecked.
 *
 * Each rejection below is a value that parses as *something* — the point is not "malformed JSON",
 * which Jackson already stops, but well-formed input that is not a payable amount.
 */
class ProposePaymentArgsTest {

    private val mapper = ObjectMapper()

    private fun args(
        fromAccountId: String? = "acc-1",
        toIban: String? = "CZ6508000000192000145399",
        amount: String? = "12.34",
        currency: String? = "CZK",
    ) = mapper.createObjectNode().apply {
        fromAccountId?.let { put("fromAccountId", it) }
        toIban?.let { put("toIban", it) }
        amount?.let { put("amount", it) }
        currency?.let { put("currency", it) }
    }

    @Test
    fun `a well-formed proposal passes`() {
        assertThatCode { ProposePaymentArgs.validate(args()) }.doesNotThrowAnyException()
    }

    @Test
    fun `an IBAN that fails the mod-97 check is rejected`() {
        // One digit changed from the valid CZ IBAN above: the right length and shape, wrong checksum.
        // Reusing openbank-libs-domain's Iban means this is the same check the payment services make,
        // not a second regex that agrees with them only by coincidence.
        assertThatThrownBy { ProposePaymentArgs.validate(args(toIban = "CZ6508000000192000145398")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("valid IBAN")
    }

    @Test
    fun `scientific notation is rejected even though BigDecimal accepts it`() {
        // BigDecimal("1E3") == 1000. An amount that reads as 1E3 in an audit log is not a
        // reviewable proposal, and a human disposing of it would not see a thousand.
        assertThatThrownBy { ProposePaymentArgs.validate(args(amount = "1E3")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("plain decimal")
    }

    @Test
    fun `a negative or zero amount is rejected`() {
        for (bad in listOf("-1.00", "0", "0.00")) {
            assertThatThrownBy { ProposePaymentArgs.validate(args(amount = bad)) }
                .describedAs(bad)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `an amount finer than any currency's minor unit is rejected`() {
        // Nothing executing this could pay 0.00001; it would be rounded somewhere downstream, which
        // is the classic place a cent goes missing without anything logging that it did.
        assertThatThrownBy { ProposePaymentArgs.validate(args(amount = "1.00001")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a currency that is not ISO 4217 is rejected`() {
        assertThatThrownBy { ProposePaymentArgs.validate(args(currency = "XYZ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ISO 4217")
        // Shape-only checks are not enough: "czk" is three letters and still wrong.
        assertThatThrownBy { ProposePaymentArgs.validate(args(currency = "czk")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `every field is required, and a missing one names itself`() {
        assertThatThrownBy { ProposePaymentArgs.validate(args(fromAccountId = null)) }
            .hasMessageContaining("fromAccountId")
        assertThatThrownBy { ProposePaymentArgs.validate(args(toIban = null)) }
            .hasMessageContaining("toIban")
        assertThatThrownBy { ProposePaymentArgs.validate(args(amount = null)) }
            .hasMessageContaining("amount")
        assertThatThrownBy { ProposePaymentArgs.validate(args(currency = null)) }
            .hasMessageContaining("currency")
    }

    @Test
    fun `a non-string field is treated as absent, not coerced`() {
        // A model emitting `"amount": 12.34` as a JSON number must not slip through: the schema says
        // string precisely because a number has already been through a double by the time we see it.
        val n = mapper.createObjectNode()
            .put("fromAccountId", "acc-1")
            .put("toIban", "CZ6508000000192000145399")
            .put("currency", "CZK")
        n.put("amount", 12.34)
        assertThatThrownBy { ProposePaymentArgs.validate(n) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("amount")
    }

    @Test
    fun `whitespace around a value does not defeat validation`() {
        assertThatCode { ProposePaymentArgs.validate(args(toIban = "  CZ6508000000192000145399 ")) }
            .doesNotThrowAnyException()
        assertThat(true).isTrue()
    }
}
