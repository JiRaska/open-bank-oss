// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** ADR-0269 rule 4: the only place an instalment or an APRC may come from. */
class CreditQuoteCalculatorTest {

    private val now: Instant = Instant.parse("2026-08-21T10:00:00Z")
    private val firstDue: LocalDate = LocalDate.parse("2026-10-01")
    private val validity: Duration = Duration.ofDays(14)

    private fun czk(amount: String) = Money.of(BigDecimal(amount), "CZK")

    private fun request(
        principal: String = "250000",
        term: Int = 48,
        rate: String = "0.079",
        upfront: String? = null,
        monthly: String? = null,
    ) = CreditQuoteRequest(
        principal = czk(principal),
        termMonths = term,
        nominalAnnualRate = BigDecimal(rate),
        upfrontFee = upfront?.let { czk(it) },
        monthlyFee = monthly?.let { czk(it) },
    )

    private fun quote(r: CreditQuoteRequest = request()) = CreditQuoteCalculator.quote(r, now, validity, firstDue)

    @Test
    fun `a single repayment of 101 one year after drawing 100 has one percent APRC`() {
        val q = CreditQuoteCalculator.quote(
            request(principal = "100", term = 1, rate = "0.12"),
            now,
            validity,
            LocalDate.parse("2027-08-21"),
        )

        assertThat(q.totalPayable).isEqualTo(czk("101"))
        assertThat(q.aprc).isEqualByComparingTo("0.010000")
    }

    @Test
    fun `the original four argument JVM signature still prices an existing caller`() {
        val originalMethod = CreditQuoteCalculator::class.java.getMethod(
            "quote",
            CreditQuoteRequest::class.java,
            Instant::class.java,
            Duration::class.java,
            LocalDate::class.java,
        )
        val q = originalMethod.invoke(
            CreditQuoteCalculator,
            request(principal = "100", term = 1, rate = "0.12"),
            now,
            validity,
            LocalDate.parse("2027-08-21"),
        ) as CreditQuote

        assertThat(q.aprc).isEqualByComparingTo("0.010000")
    }

    @Test
    fun `deferring the same repayment lowers APRC and preserves the regular monthly root`() {
        val r = request(principal = "100", term = 1, rate = "0.12")
        val monthly = CreditQuoteCalculator.quote(r, now, validity, LocalDate.parse("2026-09-21"))
        val yearly = CreditQuoteCalculator.quote(r, now, validity, LocalDate.parse("2027-08-21"))

        // 100 * (1 + X)^(1/12) = 101, so X = 1.01^12 - 1.
        assertThat(monthly.aprc).isEqualByComparingTo("0.126825")
        assertThat(yearly.totalPayable).isEqualTo(monthly.totalPayable)
        assertThat(yearly.aprc).isLessThan(monthly.aprc)
    }

    @Test
    fun `an explicit future drawdown date is independent of the quote creation date`() {
        val q = CreditQuoteCalculator.quote(
            request(principal = "100", term = 1, rate = "0.12"),
            now,
            validity,
            firstDueDate = LocalDate.parse("2027-09-21"),
            drawdownDate = LocalDate.parse("2026-09-21"),
        )

        assertThat(q.aprc).isEqualByComparingTo("0.010000")
        assertThat(q.validUntil).isEqualTo(now.plus(validity))
    }

    @Test
    fun `the default drawdown date uses UTC even near midnight`() {
        val q = CreditQuoteCalculator.quote(
            request(principal = "100", term = 1, rate = "0.12"),
            Instant.parse("2026-08-21T23:59:59Z"),
            validity,
            LocalDate.parse("2027-08-21"),
        )

        assertThat(q.aprc).isEqualByComparingTo("0.010000")
    }

    @Test
    fun `residual days use the complete year ending at the backwards month anchor`() {
        // Whole months are removed first: Apr 1 -> Mar 1, leaving Feb 15 -> Mar 1.
        assertSinglePaymentAprc("2024-02-15", "2024-04-01", 1.0 / 12 + 15.0 / 366)
        assertSinglePaymentAprc("2025-02-15", "2025-04-01", 1.0 / 12 + 14.0 / 365)
        // The residual endpoint is in 2025, but its preceding full year includes Feb 29, 2024.
        assertSinglePaymentAprc("2024-12-15", "2025-02-01", 1.0 / 12 + 17.0 / 366)
    }

    @Test
    fun `end of month intervals follow backwards calendar months without an implicit roll`() {
        assertSinglePaymentAprc("2024-01-31", "2024-02-29", 29.0 / 366)
        assertSinglePaymentAprc("2025-01-31", "2025-02-28", 28.0 / 366)
        assertSinglePaymentAprc("2024-02-29", "2024-03-31", 1.0 / 12)
        assertSinglePaymentAprc("2024-01-31", "2024-03-31", 2.0 / 12)
    }

    private fun assertSinglePaymentAprc(drawdown: String, due: String, years: Double) {
        val q = CreditQuoteCalculator.quote(
            request(principal = "100", term = 1, rate = "0.12"),
            now,
            validity,
            firstDueDate = LocalDate.parse(due),
            drawdownDate = LocalDate.parse(drawdown),
        )
        // Independent closed-form root of 100 * (1 + X)^years = 101.
        val expected = BigDecimal.valueOf(Math.pow(1.01, 1 / years) - 1).setScale(6, RoundingMode.HALF_UP)
        assertThat(q.aprc).describedAs("drawdown %s, payment %s", drawdown, due).isEqualByComparingTo(expected)
    }

    // ── Consistency with the loan that would actually be booked ───────────────

    @Test
    fun `the instalment is the schedule's own instalment, not a second formula`() {
        val r = request()
        val schedule = Amortization.schedule(
            principal = r.principal,
            nominalAnnualRate = r.nominalAnnualRate,
            termPeriods = r.termMonths,
            firstDueDate = firstDue,
        )
        assertThat(quote(r).monthlyPayment).isEqualTo(schedule.installments.first().payment)
    }

    @Test
    fun `total payable equals principal plus the total cost of credit`() {
        val q = quote()
        assertThat(q.totalPayable).isEqualTo(q.principal + q.totalCostOfCredit)
    }

    // ── Fees are priced, not decorated ────────────────────────────────────────

    @Test
    fun `an upfront fee raises the APRC above the nominal rate`() {
        val withFee = quote(request(upfront = "5000")).aprc
        val withoutFee = quote(request()).aprc
        assertThat(withFee).isGreaterThan(withoutFee)
        assertThat(withFee).isGreaterThan(BigDecimal("0.079"))
    }

    @Test
    fun `a monthly fee raises both the instalment and the APRC`() {
        val plain = quote(request())
        val withFee = quote(request(monthly = "49"))
        assertThat(withFee.monthlyPayment.amount).isGreaterThan(plain.monthlyPayment.amount)
        assertThat(withFee.aprc).isGreaterThan(plain.aprc)
    }

    @Test
    fun `a monthly fee is counted for every month of the term in the total`() {
        val plain = quote(request())
        val withFee = quote(request(monthly = "100"))
        val difference = withFee.totalPayable - plain.totalPayable
        // 100 in monthly charges across all 48 installments.
        assertThat(difference.amount).isEqualByComparingTo("4800") // 100 × 48
    }

    @Test
    fun `an upfront fee is part of the total cost even though it is never instalment money`() {
        val plain = quote(request())
        val withFee = quote(request(upfront = "3000"))
        assertThat((withFee.totalCostOfCredit - plain.totalCostOfCredit).amount).isEqualByComparingTo("3000")
    }

    // ── The APRC is never faked ───────────────────────────────────────────────

    @Test
    fun `an interest-free, fee-free loan reports no APRC rather than zero`() {
        // Zero really is "nothing to disclose" here, and null is how the caller is forced to render
        // an absent figure. A 0.00% badge would read as an advertised rate the bank did not price.
        assertThat(quote(request(rate = "0")).aprc).isNull()
    }

    @Test
    fun `an interest-free loan WITH a fee still has a real APRC`() {
        val q = quote(request(rate = "0", upfront = "5000"))
        assertThat(q.aprc).isNotNull()
        assertThat(q.aprc).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `the APRC exceeds the nominal rate whenever any charge exists`() {
        val q = quote(request(rate = "0.079", upfront = "2000", monthly = "29"))
        assertThat(q.aprc).isGreaterThan(q.nominalAnnualRate)
    }

    // ── A quote is not an offer ───────────────────────────────────────────────

    @Test
    fun `a quote carries a validity and, structurally, nothing an offer would have`() {
        val q = quote()
        assertThat(q.validUntil).isEqualTo(now.plus(validity))
        // A binding offer has an id and an accept path; a quote must have neither, so that no
        // caller can treat an indicative price as something the bank committed to.
        val fields = CreditQuote::class.java.declaredFields.map { it.name.lowercase() }
        assertThat(fields).noneMatch { it.contains("offer") || it.contains("accept") }
    }

    // ── Refusals ──────────────────────────────────────────────────────────────

    @Test
    fun `a fee larger than the principal is refused — the customer would receive nothing`() {
        assertThatThrownBy { request(principal = "10000", upfront = "10000") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a fee in another currency is refused rather than added blindly`() {
        assertThatThrownBy {
            CreditQuoteRequest(
                principal = czk("100000"),
                termMonths = 12,
                nominalAnnualRate = BigDecimal("0.05"),
                upfrontFee = Money.of(BigDecimal("100"), "EUR"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a zero or negative term is refused`() {
        assertThatThrownBy { request(term = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the first repayment must be strictly after drawdown`() {
        val drawdown = LocalDate.parse("2026-08-21")
        for (due in listOf(drawdown, drawdown.minusDays(1))) {
            assertThatThrownBy {
                CreditQuoteCalculator.quote(request(), now, validity, due, drawdown)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("firstDueDate must be after drawdownDate")
        }
    }
}
