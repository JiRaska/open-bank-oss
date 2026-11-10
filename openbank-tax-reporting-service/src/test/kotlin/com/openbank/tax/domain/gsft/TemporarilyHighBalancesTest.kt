// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.gsft

import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class TemporarilyHighBalancesTest {
    private val day = LocalDate.of(2026, 11, 9)
    private val rates = FxRates(day, mapOf("EUR" to BigDecimal("25.000")))
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val acme = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private fun account(principal: String, holder: UUID, currency: String = "CZK", synthetic: Boolean = false) =
        DepositAccount(
            UUID.randomUUID(),
            "CZ6508000000192000145399",
            currency,
            BigDecimal(principal),
            BigDecimal.ZERO,
            listOf(Holding(holder, null)),
            synthetic,
        )

    private fun credit(
        acc: DepositAccount,
        amount: String,
        on: LocalDate = day.minusMonths(1),
        party: UUID = alice,
        applied: Boolean = true,
    ) = QualifyingCredit(
        party,
        acc.accountId,
        BigDecimal(amount),
        on,
        QualifyingEvent.RESIDENTIAL_PROPERTY_SALE,
        "KN-V-1/2026",
        applied,
    )

    private fun run(
        acc: DepositAccount,
        vararg credits: QualifyingCredit,
        natural: Boolean = true,
        party: UUID = alice,
    ) = InsuredClaims.assemble(
        day,
        listOf(Depositor(party, null, false, natural)),
        listOf(acc),
        rates,
        credits.toList(),
    )

    @Test
    fun `pays the credited part above the standard limit`() {
        val acc = account("3000000", alice)
        val r = run(acc, credit(acc, "400000"))
        val claim = r.claims.single()
        assertThat(claim.compensationCzk).isEqualByComparingTo("2500000.00")
        assertThat(claim.increasedCompensationCzk).isEqualByComparingTo("400000.00")
        assertThat(r.temporarilyHighClaims).isEqualTo(1)
        assertThat(r.temporarilyHighCompensationCzk).isEqualByComparingTo("400000.00")
        assertThat(r.refusedQualifyingCredits).isZero()
    }

    @Test
    fun `never exceeds the balance above the standard limit`() {
        val acc = account("2600000", alice)
        assertThat(run(acc, credit(acc, "900000")).claims.single().increasedCompensationCzk)
            .isEqualByComparingTo("100000.00")
    }

    @Test
    fun `nothing extra when the balance is within the standard limit`() {
        val acc = account("2000000", alice)
        val r = run(acc, credit(acc, "900000"))
        assertThat(r.claims.single().increasedCompensationCzk).isEqualByComparingTo("0")
        assertThat(r.temporarilyHighClaims).isZero()
    }

    @Test
    fun `caps the increase at the EUR 100 000 equivalent`() {
        val acc = account("9000000", alice)
        assertThat(run(acc, credit(acc, "6000000")).claims.single().increasedCompensationCzk)
            .isEqualByComparingTo("2500000.00")
    }

    @Test
    fun `converts a foreign-currency credit at the relevant-day rate`() {
        val acc = account("120000", alice, currency = "EUR")
        assertThat(run(acc, credit(acc, "1000")).claims.single().increasedCompensationCzk)
            .isEqualByComparingTo("25000.00")
    }

    @Test
    fun `window includes the crediting day and the day three months later`() {
        val acc = account("3000000", alice)
        assertThat(run(acc, credit(acc, "1000", on = day)).claims.single().increasedCompensationCzk)
            .isEqualByComparingTo("1000.00")
        assertThat(
            run(acc, credit(acc, "1000", on = LocalDate.of(2026, 8, 9))).claims.single().increasedCompensationCzk,
        )
            .isEqualByComparingTo("1000.00")
    }

    @Test
    fun `refuses a credit outside the window`() {
        val acc = account("3000000", alice)
        val before = run(acc, credit(acc, "1000", on = LocalDate.of(2026, 8, 8)))
        assertThat(before.claims.single().increasedCompensationCzk).isEqualByComparingTo("0")
        assertThat(before.refusedQualifyingCredits).isEqualTo(1)
        val after = run(acc, credit(acc, "1000", on = day.plusDays(1)))
        assertThat(after.refusedQualifyingCredits).isEqualTo(1)
    }

    @Test
    fun `refuses a credit the depositor did not apply for`() {
        val acc = account("3000000", alice)
        val r = run(acc, credit(acc, "1000", applied = false))
        assertThat(r.claims.single().increasedCompensationCzk).isEqualByComparingTo("0")
        assertThat(r.refusedQualifyingCredits).isEqualTo(1)
    }

    @Test
    fun `refuses a credit of a depositor not known to be a natural person`() {
        val acc = account("3000000", acme)
        val r = run(acc, credit(acc, "1000", party = acme), natural = false, party = acme)
        assertThat(r.claims.single().increasedCompensationCzk).isEqualByComparingTo("0")
        assertThat(r.refusedQualifyingCredits).isEqualTo(1)
    }

    @Test
    fun `refuses a credit on a synthetic account`() {
        val live = account("3000000", alice)
        val fake = account("3000000", alice, synthetic = true)
        val r = InsuredClaims.assemble(
            day,
            listOf(Depositor(alice, null, false, true)),
            listOf(live, fake),
            rates,
            listOf(credit(fake, "1000")),
        )
        assertThat(r.claims.single().increasedCompensationCzk).isEqualByComparingTo("0")
        assertThat(r.refusedQualifyingCredits).isEqualTo(1)
    }

    @Test
    fun `fails on a credit naming an account the party does not hold`() {
        val acc = account("3000000", alice)
        val other = account("1", alice)
        assertThatThrownBy { run(acc, credit(other, "1000")) }.isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `a credit needs evidence and a positive amount`() {
        val id = UUID.randomUUID()
        assertThatThrownBy { QualifyingCredit(alice, id, BigDecimal.ONE, day, QualifyingEvent.SEVERANCE, " ", true) }
            .isInstanceOf(TaxValidationException::class.java)
        assertThatThrownBy { QualifyingCredit(alice, id, BigDecimal.ZERO, day, QualifyingEvent.SEVERANCE, "x", true) }
            .isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `the increase is carried into the batch and never into the files`() {
        val acc = account("3000000", alice)
        val plain = run(acc)
        val high = run(acc, credit(acc, "400000"))
        val type = EntitledPersonType.values().first()
        val id = DepositorIdentity(alice, "9001011234", type, "Alice", "S 1", "Praha", "11000", "CZ", "CS")
        val header = BatchHeader("0800", 1, java.time.LocalDateTime.of(2026, 11, 10, 8, 0), BatchMode.TEST)
        val a = GsftBatchRenderer.render(plain, listOf(acc), listOf(id), rates, header)
        val b = GsftBatchRenderer.render(high, listOf(acc), listOf(id), rates, header)
        assertThat(b.temporarilyHighClaims).isEqualTo(1)
        assertThat(b.temporarilyHighCompensationCzk).isEqualByComparingTo("400000.00")
        assertThat(b.totalPaymentCzk).isEqualByComparingTo(a.totalPaymentCzk)
        assertThat(b.files.keys).isEqualTo(a.files.keys)
        b.files.forEach { (k, v) -> assertThat(v).isEqualTo(a.files.getValue(k)) }
    }
}
