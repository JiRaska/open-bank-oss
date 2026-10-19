// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.openbank.swift.domain.institution.ReleaseValidation
import com.openbank.swift.domain.institution.ReleasedPaymentValidator
import com.openbank.swift.institution.InstitutionTransferFixtures.TODAY
import com.openbank.swift.institution.InstitutionTransferFixtures.parsed
import com.openbank.swift.institution.InstitutionTransferFixtures.release
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ReleasedPaymentValidatorTest {

    private fun validate(m: Map<String, Any?>) = ReleasedPaymentValidator.validate(parsed(m), TODAY, 1)

    @Test
    fun `a valid release on each rail passes`() {
        for (rail in listOf("CERTIS", "TARGET2", "SWIFT")) {
            assertThat(validate(release(rail))).describedAs(rail).isInstanceOf(ReleaseValidation.Valid::class.java)
        }
    }

    @Test
    fun `unknown extra fields are tolerated and optional ones may be missing`() {
        val m =
            release(
                extra = mapOf("someFutureField" to mapOf("x" to 1)),
                drop = setOf("intermediaryBic", "remittanceInformation"),
            )
        assertThat(validate(m)).isInstanceOf(ReleaseValidation.Valid::class.java)
    }

    @Test
    fun `ssiVersion is optional, carried when present, refused when malformed`() {
        val with = validate(release(extra = mapOf("ssiVersion" to 7))) as ReleaseValidation.Valid
        assertThat(with.release.ssiVersion).isEqualTo(7L)
        val without = validate(release()) as ReleaseValidation.Valid
        assertThat(without.release.ssiVersion).isNull()
        assertThat(
            validate(release(extra = mapOf("ssiVersion" to null))),
        ).isInstanceOf(ReleaseValidation.Valid::class.java)
        for (bad in listOf("7", 1.5, 0, -1)) {
            assertThat(
                code(release(extra = mapOf("ssiVersion" to bad))),
            ).describedAs("ssiVersion=%s", bad).isEqualTo("FF01")
        }
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("negatives")
    fun `each rule refuses with its ISO reason code`(name: String, m: Map<String, Any?>, expected: String) {
        assertThat(code(m)).isEqualTo(expected)
    }

    @Test
    fun `IBAN check digits are verified and plain domestic account numbers are accepted`() {
        assertThat(ReleasedPaymentValidator.isAccount("CZ6508000000192000145399")).isTrue()
        assertThat(ReleasedPaymentValidator.isAccount("CZ6608000000192000145399")).isFalse()
        assertThat(ReleasedPaymentValidator.isAccount("19-2000145399/0800")).isTrue()
        assertThat(ReleasedPaymentValidator.isAccount("acct with spaces")).isFalse()
    }

    private fun code(m: Map<String, Any?>): String? = (validate(m) as? ReleaseValidation.Invalid)?.rejection?.reasonCode

    companion object {
        private fun with(vararg kv: Pair<String, Any?>) = release(extra = mapOf(*kv))
        private val debtorOk = mapOf(
            "bic" to "OPENCZPPXXX",
            "accountNumber" to "CZ6508000000192000145399",
            "glAccountingClass" to "NOSTRO",
        )
        private val creditorOk = mapOf(
            "bic" to "BNPAFRPPXXX",
            "accountNumber" to "FR1420041010050500013M02606",
            "name" to "Counterparty Bank",
        )

        @JvmStatic
        fun negatives(): List<Arguments> = listOf(
            Arguments.of("paymentId not a UUID", with("paymentId" to "nope"), "FF01"),
            Arguments.of("paymentId missing", release(drop = setOf("paymentId")), "FF01"),
            Arguments.of("idempotencyKey foreign shape", with("idempotencyKey" to "other:1"), "FF01"),
            Arguments.of(
                "idempotencyKey too long",
                with("idempotencyKey" to "treasury:" + "a".repeat(250) + ":payment"),
                "FF01",
            ),
            Arguments.of("unknown rail", with("rail" to "SEPA"), "FF01"),
            Arguments.of("currency not ISO", with("rail" to "SWIFT", "currency" to "XYZ"), "AM03"),
            Arguments.of("currency lower case", with("rail" to "SWIFT", "currency" to "usd"), "AM03"),
            Arguments.of("currency off-rail", with("rail" to "TARGET2", "currency" to "CZK"), "AM03"),
            Arguments.of("amount one decimal", with("amount" to "10.0"), "AM12"),
            Arguments.of("amount as number", with("amount" to 10.00), "AM12"),
            Arguments.of("amount zero", with("amount" to "0.00"), "AM12"),
            Arguments.of("amount negative", with("amount" to "-1.00"), "AM12"),
            Arguments.of(
                "amount finer than JPY minor unit",
                with(
                    "rail" to "SWIFT",
                    "currency" to "JPY",
                    "amount" to "100.50",
                ),
                "AM12",
            ),
            Arguments.of("valueDate malformed", with("valueDate" to "19.10.2026"), "DT01"),
            Arguments.of("valueDate beyond tolerance", with("valueDate" to "2026-10-17"), "DT01"),
            Arguments.of("debtor BIC", with("debtor" to debtorOk + ("bic" to "OPEN")), "RC01"),
            Arguments.of("creditor BIC", with("creditor" to creditorOk + ("bic" to "bnpafrpp")), "RC01"),
            Arguments.of("intermediary BIC", with("intermediaryBic" to "X"), "RC01"),
            Arguments.of("creditor equals debtor", with("creditor" to creditorOk + ("bic" to "OPENCZPPXXX")), "RC01"),
            Arguments.of(
                "debtor account",
                with("debtor" to debtorOk + ("accountNumber" to "CZ0000000000000000000000")),
                "AC01",
            ),
            Arguments.of("creditor account", with("creditor" to creditorOk + ("accountNumber" to "x y")), "AC01"),
            Arguments.of("gl class missing", with("debtor" to debtorOk - "glAccountingClass"), "FF01"),
            Arguments.of(
                "creditor name too long",
                with("creditor" to creditorOk + ("name" to "n".repeat(141))),
                "FF01",
            ),
            Arguments.of("remittance too long", with("remittanceInformation" to "r".repeat(141)), "FF01"),
            Arguments.of("dealIds non-UUID", with("dealIds" to listOf("x")), "FF01"),
            Arguments.of("flowIds empty", with("flowIds" to emptyList<String>()), "FF01"),
            Arguments.of("releasedBy blank", with("releasedBy" to " "), "FF01"),
            Arguments.of("releasedAt not RFC 3339", with("releasedAt" to "yesterday"), "FF01"),
            Arguments.of("synthetic not boolean", with("synthetic" to "true"), "FF01"),
        )
    }
}
