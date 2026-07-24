// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.ap2

import com.openbank.ap2.domain.Ap2Mandate
import com.openbank.ap2.domain.ConstraintResult
import com.openbank.ap2.domain.MandateConstraintChecks
import com.openbank.ap2.domain.MandateConstraints
import com.openbank.ap2.domain.MandateKind
import com.openbank.ap2.domain.MandateSignatureAlgorithm
import com.openbank.ap2.domain.PresentedPayment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** Pure-domain constraint checks (ADR-0193 §1 stage 2): no crypto, no framework. */
class MandateConstraintChecksTest {

    private val expiry = Instant.parse("2026-12-31T00:00:00Z")

    private fun mandate(payee: String = "CZ6508000000192000145399", cap: Long = 100_00, currency: String = "CZK") =
        Ap2Mandate(
            kind = MandateKind.PAYMENT,
            issuer = "issuer-1",
            subject = "cust-1",
            constraints = MandateConstraints(payee, cap, currency, expiry),
            signingInput = "h.p",
            signatureB64 = "x",
            algorithm = MandateSignatureAlgorithm.ED25519,
        )

    private fun payment(
        payee: String = "CZ6508000000192000145399",
        amount: Long = 50_00,
        currency: String = "CZK",
        at: Instant = Instant.parse("2026-06-01T00:00:00Z"),
    ) = PresentedPayment(payee, amount, currency, at)

    @Test
    fun `in-bounds payment satisfies the mandate`() {
        assertThat(MandateConstraintChecks.check(mandate(), payment())).isEqualTo(ConstraintResult.Ok)
    }

    @Test
    fun `expired mandate fails`() {
        val r = MandateConstraintChecks.check(mandate(), payment(at = Instant.parse("2027-01-01T00:00:00Z")))
        assertThat(r).isInstanceOf(ConstraintResult.Violated::class.java)
        assertThat((r as ConstraintResult.Violated).reasons).anyMatch { it.contains("expired") }
    }

    @Test
    fun `amount over cap fails`() {
        val r = MandateConstraintChecks.check(mandate(cap = 40_00), payment(amount = 50_00))
        assertThat((r as ConstraintResult.Violated).reasons).anyMatch { it.contains("exceeds cap") }
    }

    @Test
    fun `currency mismatch fails`() {
        val r = MandateConstraintChecks.check(mandate(currency = "EUR"), payment(currency = "CZK"))
        assertThat((r as ConstraintResult.Violated).reasons).anyMatch { it.contains("currency") }
    }

    @Test
    fun `payee mismatch fails`() {
        val r = MandateConstraintChecks.check(mandate(), payment(payee = "CZ0000000000000000000000"))
        assertThat((r as ConstraintResult.Violated).reasons).anyMatch { it.contains("payee") }
    }

    @Test
    fun `multiple violations are all reported`() {
        val r = MandateConstraintChecks.check(
            mandate(cap = 10_00, currency = "EUR"),
            payment(amount = 90_00, currency = "CZK", at = Instant.parse("2027-01-01T00:00:00Z")),
        )
        assertThat((r as ConstraintResult.Violated).reasons).hasSizeGreaterThanOrEqualTo(3)
    }
}
