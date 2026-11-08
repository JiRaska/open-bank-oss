// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The APRC is the number a customer compares lenders on and a regulator checks, so these tests
 * pin it against cases with an independently known answer rather than against this implementation's
 * own output.
 */
class AprcTest {

    private fun years(months: Int): BigDecimal =
        BigDecimal(months).divide(BigDecimal(12), java.math.MathContext.DECIMAL128)

    private fun advance(amount: String) = Aprc.CashFlow(BigDecimal.ZERO, BigDecimal(amount))

    private fun monthlyPayments(amount: String, count: Int) =
        (1..count).map { Aprc.CashFlow(years(it), BigDecimal(amount)) }

    private fun percent(rate: BigDecimal?): BigDecimal? =
        rate?.multiply(BigDecimal(100))?.setScale(2, RoundingMode.HALF_UP)

    // ── Known answers ─────────────────────────────────────────────────────────

    @Test
    fun `a single repayment of 110 on an advance of 100 after one year is 10 percent`() {
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("110"))))
        assertThat(percent(aprc)).isEqualByComparingTo("10.00")
    }

    @Test
    fun `doubling the money in one year is 100 percent`() {
        val aprc = Aprc.solve(listOf(advance("1000")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("2000"))))
        assertThat(percent(aprc)).isEqualByComparingTo("100.00")
    }

    @Test
    fun `repaying exactly what was advanced, at the moment it was advanced, is zero cost and has no positive rate`() {
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("100"))))
        assertThat(aprc).isNull()
    }

    @Test
    fun `an interest-free loan repaid later has no positive solution and must not be reported as zero`() {
        // Repaying 100 a year after receiving 100: the equation balances only at X = 0, which the
        // solver reports as "no solution" rather than inventing a rate. Null must reach the caller —
        // rendering it as 0% would read to a customer as free credit that has actually been priced.
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("100"))))
        assertThat(aprc).isNull()
    }

    // ── The property that matters: charges raise the APRC above the nominal rate ─

    @Test
    fun `an arrangement fee pushes the APRC above the nominal rate`() {
        // 12 monthly payments of 8,884.88 on 100,000 is ~6.5% nominal. Adding a 2,000 fee at
        // drawdown means the customer receives 98,000 for the same repayments, so the true cost
        // rises — this is the entire reason the APRC exists and the case a nominal-rate shortcut
        // gets wrong.
        val withoutFee = Aprc.solve(listOf(advance("100000")), monthlyPayments("8884.88", 12))
        val withFee = Aprc.solve(listOf(advance("98000")), monthlyPayments("8884.88", 12))
        assertThat(withFee).isGreaterThan(withoutFee)
    }

    @Test
    fun `a longer term at the same instalment costs less per year`() {
        val short = Aprc.solve(listOf(advance("100000")), monthlyPayments("8884.88", 12))
        val long = Aprc.solve(listOf(advance("100000")), monthlyPayments("8884.88", 18))
        assertThat(long).isGreaterThan(short)
    }

    // ── Refusals ──────────────────────────────────────────────────────────────

    @Test
    fun `paying back less than was advanced has no positive solution`() {
        val aprc = Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("50"))))
        assertThat(aprc).isNull()
    }

    @Test
    fun `a negative payment is refused rather than silently inverting the equation`() {
        assertThatThrownBy {
            Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("-110"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a cash flow before the first drawdown is refused`() {
        assertThatThrownBy {
            Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal("-1"), BigDecimal("110"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `no payments at all is refused, not answered with zero`() {
        assertThatThrownBy { Aprc.solve(listOf(advance("100")), emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    fun `the same inputs always produce the same rate to the disclosed precision`() {
        val first = Aprc.solve(listOf(advance("250000")), monthlyPayments("6100.55", 48))
        val second = Aprc.solve(listOf(advance("250000")), monthlyPayments("6100.55", 48))
        assertThat(first).isEqualByComparingTo(second)
        assertThat(first!!.scale()).isEqualTo(6)
    }

    @Test
    fun `equal-time grouping and amount scaling retain the one-year answer`() {
        for (scale in listOf("0.0001", "1", "1000000")) {
            val factor = BigDecimal(scale)
            val actual = Aprc.solve(
                listOf(
                    advance(BigDecimal("20").multiply(factor).toPlainString()),
                    advance(BigDecimal("80").multiply(factor).toPlainString()),
                ),
                listOf(
                    Aprc.CashFlow(BigDecimal.ONE, BigDecimal("33").multiply(factor)),
                    Aprc.CashFlow(BigDecimal.ONE, BigDecimal("77").multiply(factor)),
                ),
            )
            assertThat(actual).isEqualByComparingTo("0.100000")
        }
    }

    @Test
    fun `a unique root with reversed balance orientation is supported`() {
        val actual = Aprc.solve(
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("110"))),
            listOf(Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("100"))),
        )
        assertThat(actual).isEqualByComparingTo("0.100000")
    }

    @Test
    fun `one mixed-timing root inside the search range must not be lost`() {
        // Multiplying minus PV balance by y^2 gives 100*(y-1.1)*(y-150).
        val actual = Aprc.solve(
            listOf(advance("100"), Aprc.CashFlow(BigDecimal("2"), BigDecimal("16500"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("15110"))),
        )
        assertThat(actual).isEqualByComparingTo("0.100000")
    }

    @Test
    fun `two admissible roots are not resolved by choosing the first`() {
        val actual = Aprc.solve(
            listOf(advance("100"), Aprc.CashFlow(BigDecimal("2"), BigDecimal("1000"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("1050"))),
        )
        assertThat(actual).isNull()
    }

    @Test
    fun `an unresolved stationary root is not presented as a computed rate`() {
        // A single distinct root at r=1, with multiplicity two. Null here means unresolved,
        // not two financial choices or a proof that no mathematical root exists.
        val actual = Aprc.solve(
            listOf(advance("100"), Aprc.CashFlow(BigDecimal("2"), BigDecimal("400"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("400"))),
        )
        assertThat(actual).isNull()
    }

    @Test
    fun `the upper endpoint is included without expanding the rate range`() {
        assertThat(Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("10100")))))
            .isEqualByComparingTo("100.000000")
        assertThat(Aprc.solve(listOf(advance("100")), listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("10100.001")))))
            .isNull()
    }

    @Test
    fun `a two-year upper endpoint is retained by the general scalar route`() {
        // 100*(1+100)^2 = 1020100, and positive two-year repayments are strictly decreasing in rate.
        // This fixture cannot use the one-year rational branch.
        val actual = Aprc.solve(
            listOf(advance("100")),
            listOf(Aprc.CashFlow(BigDecimal("2"), BigDecimal("1020100"))),
        )
        assertThat(actual).isEqualByComparingTo("100.000000")
    }

    @Test
    fun `fractional mixed timing is not narrowed to integer quadratic fixtures`() {
        // With z=sqrt(1+r), minus balance*z^2=100*(z-1.1)*(z-150).
        // Only r=1.1^2-1=0.21 is inside 0..100.
        val actual = Aprc.solve(
            listOf(advance("100"), Aprc.CashFlow(BigDecimal.ONE, BigDecimal("16500"))),
            listOf(Aprc.CashFlow(BigDecimal("0.5"), BigDecimal("15110"))),
        )
        assertThat(actual).isEqualByComparingTo("0.210000")
    }

    @Test
    fun `mixed root discovery supports reversed balance orientation`() {
        val actual = Aprc.solve(
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("15110"))),
            listOf(
                Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("100")),
                Aprc.CashFlow(BigDecimal("2"), BigDecimal("16500")),
            ),
        )
        assertThat(actual).isEqualByComparingTo("0.100000")
    }

    @Test
    fun `two close roots are not merged because they round to the same rate`() {
        // The two exact roots are 0.1000001 and 0.1000002. A null is permitted uncertainty,
        // not a claim that the mathematical root count has been evaluated successfully.
        val actual = Aprc.solve(
            listOf(advance("100"), Aprc.CashFlow(BigDecimal("2"), BigDecimal("121.000033000002"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("220.00003"))),
        )
        assertThat(actual).isNull()
    }

    @Test
    fun `rounding near the nonannual upper bound does not admit an outside root`() {
        val inside = Aprc.solve(
            listOf(advance("100")),
            listOf(Aprc.CashFlow(BigDecimal("2"), BigDecimal("1020099.79800001"))),
        )
        assertThat(inside).isEqualByComparingTo("99.999990")
        val outside = Aprc.solve(
            listOf(advance("100")),
            listOf(Aprc.CashFlow(BigDecimal("2"), BigDecimal("1020100.000001"))),
        )
        assertThat(outside).isNull()
    }

    @Test
    fun `an exactly balanced nonannual midpoint needs an exact equality witness`() {
        val actual = Aprc.solve(
            listOf(advance("100")),
            listOf(Aprc.CashFlow(BigDecimal("2"), BigDecimal("5256.25"))),
        )
        assertThat(actual).isEqualByComparingTo("6.250000")
    }

    @Test
    fun `an excluded zero root must not hide a distinct positive mixed root`() {
        // Minus balance*y^2 = 100*(y-1)*(y-10): r=0 is excluded, r=9 is unique and admissible.
        val actual = Aprc.solve(
            listOf(advance("100"), Aprc.CashFlow(BigDecimal("2"), BigDecimal("1000"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("1100"))),
        )
        assertThat(actual).isEqualByComparingTo("9.000000")
        val zeroOnly = Aprc.solve(
            listOf(advance("100")),
            listOf(Aprc.CashFlow(BigDecimal("0.5"), BigDecimal("100"))),
        )
        assertThat(zeroOnly).isNull()
    }

    @Test
    fun `the mixed route counts an exact upper root while excluding zero`() {
        // Minus balance*y^2 = 100*(y-1)*(y-101). Only r=100 is admissible.
        val actual = Aprc.solve(
            listOf(advance("100"), Aprc.CashFlow(BigDecimal("2"), BigDecimal("10100"))),
            listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal("10200"))),
        )
        assertThat(actual).isEqualByComparingTo("100.000000")
    }

    private fun hiddenType(name: String): Class<*> = Aprc::class.java.declaredClasses.single { it.simpleName == name }

    private fun hiddenRange(low: Double, high: Double): Any = hiddenType("Range")
        .getDeclaredConstructor(java.lang.Double.TYPE, java.lang.Double.TYPE)
        .apply { isAccessible = true }
        .newInstance(low, high)

    private fun bound(value: Any, name: String): Double = value.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(value) as Double

    private fun assertEncloses(value: Any?, exact: BigDecimal) {
        assertThat(value).isNotNull()
        val interval = requireNotNull(value)
        // BigDecimal(double) is the exact binary floating-point value; valueOf would round it.
        assertThat(BigDecimal(bound(interval, "low"))).isLessThanOrEqualTo(exact)
        assertThat(BigDecimal(bound(interval, "high"))).isGreaterThanOrEqualTo(exact)
    }

    @Test
    fun `decimal enclosures include exact values at binades and underflow ties`() {
        val minimum = BigDecimal(Double.MIN_VALUE)
        val examples = listOf(
            BigDecimal.ONE,
            BigDecimal.ONE.add(BigDecimal("1E-34")),
            BigDecimal.ONE.subtract(BigDecimal("1E-34")),
            minimum,
            minimum.multiply(BigDecimal("1.5")),
            minimum.multiply(BigDecimal("0.5")),
            minimum.multiply(BigDecimal("-0.5")),
        )
        val ownerType = hiddenType("Enclosures")
        val owner = ownerType.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val method = ownerType.getDeclaredMethod("decimalRange", BigDecimal::class.java)
            .apply { isAccessible = true }
        for (exact in examples) assertEncloses(method.invoke(owner, exact), exact)
    }

    @Test
    fun `outward multiplication covers signed corners and a half-subnormal product`() {
        val type = hiddenType("Range")
        val times = type.getDeclaredMethod("times", type).apply { isAccessible = true }
        val intervals = listOf(
            doubleArrayOf(-2.0, 3.0, -5.0, 7.0),
            doubleArrayOf(-3.0, -2.0, -7.0, -5.0),
            doubleArrayOf(2.0, 3.0, -7.0, -5.0),
            doubleArrayOf(Double.MIN_VALUE, Double.MIN_VALUE, 0.5, 0.5),
            doubleArrayOf(-Double.MIN_VALUE, -Double.MIN_VALUE, 0.5, 0.5),
        )
        for (values in intervals) {
            val actual = times.invoke(hiddenRange(values[0], values[1]), hiddenRange(values[2], values[3]))
            for (left in listOf(values[0], values[1])) {
                for (right in listOf(values[2], values[3])) {
                    assertEncloses(actual, BigDecimal(left).multiply(BigDecimal(right)))
                }
            }
        }
    }

    @Test
    fun `outward sum retains an exact increment smaller than a binade ulp`() {
        val type = hiddenType("Range")
        val plus = type.getDeclaredMethod("plus", type).apply { isAccessible = true }
        val minimum = BigDecimal(Double.MIN_VALUE)
        val actual = plus.invoke(hiddenRange(1.0, 1.0), hiddenRange(Double.MIN_VALUE, Double.MIN_VALUE))
        assertEncloses(actual, BigDecimal.ONE.add(minimum))
        val negative = plus.invoke(hiddenRange(-1.0, -1.0), hiddenRange(-Double.MIN_VALUE, -Double.MIN_VALUE))
        assertEncloses(negative, BigDecimal.ONE.negate().subtract(minimum))
        val overflow = plus.invoke(
            hiddenRange(Double.MAX_VALUE, Double.MAX_VALUE),
            hiddenRange(Double.MAX_VALUE, Double.MAX_VALUE),
        )
        assertThat(overflow).isNull()
    }

    @Test
    fun `derivative error carries the exact unrounded product through two levels`() {
        val termType = hiddenType("Term")
        val constructor = termType.getDeclaredConstructor(
            BigDecimal::class.java,
            BigDecimal::class.java,
            BigDecimal::class.java,
        )
            .apply { isAccessible = true }
        fun term(time: BigDecimal, amount: BigDecimal): Any = constructor.newInstance(time, amount, BigDecimal.ZERO)
        fun decimal(value: Any, name: String): BigDecimal = termType.getDeclaredField(name)
            .apply { isAccessible = true }.get(value) as BigDecimal
        fun contains(value: Any, exact: BigDecimal) {
            val amount = decimal(value, "amount")
            val error = decimal(value, "error")
            assertThat(error).isGreaterThanOrEqualTo(BigDecimal.ZERO)
            assertThat(amount.subtract(error)).isLessThanOrEqualTo(exact)
            assertThat(amount.add(error)).isGreaterThanOrEqualTo(exact)
        }
        val workType = hiddenType("Work")
        val work = workType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val derivative = Aprc::class.java.getDeclaredMethod("derivative", List::class.java, workType)
            .apply { isAccessible = true }
        // First rounding loses 4.9E-34 near 1. The next factor9 amplifies that to4.41E-33
        // near9, beyond a fresh1E-33 rounding unit: dropping the carried error is observable.
        val exactFirst = BigDecimal.ONE.add(BigDecimal("4.9E-34"))
        val coefficient = exactFirst.divide(BigDecimal.TEN)
        val initial = listOf(
            term(BigDecimal.ZERO, BigDecimal.ONE),
            term(BigDecimal.ONE, BigDecimal.ONE),
            term(BigDecimal.TEN, coefficient),
        )
        val first = derivative.invoke(Aprc, initial, work) as List<*>
        contains(requireNotNull(first.last()), exactFirst.negate())
        val second = derivative.invoke(Aprc, first, work) as List<*>
        contains(requireNotNull(second.single()), exactFirst.multiply(BigDecimal("9")))
    }

    @Test
    fun `a critical band containing a tangent cannot leave a false unique candidate`() {
        val termType = hiddenType("Term")
        val constructor = termType.getDeclaredConstructor(
            BigDecimal::class.java,
            BigDecimal::class.java,
            BigDecimal::class.java,
        )
            .apply { isAccessible = true }
        fun term(time: String, amount: String): Any =
            constructor.newInstance(BigDecimal(time), BigDecimal(amount), BigDecimal.ZERO)
        val terms = listOf(term("0", "-1"), term("1", "8"), term("2", "-20"), term("3", "16"))
        // -y^3*balance=(y-2)^2*(y-4): tangent r1 and simple r3. A band0..1 in u
        // contains log2;1..1.25 contains log3, the other derivative root. Both are
        // true critical bands, and the first contains a mathematical zero of balance.
        val bracketType = hiddenType("Bracket")
        val bracketConstructor = bracketType.getDeclaredConstructor(
            java.lang.Double.TYPE,
            java.lang.Double.TYPE,
            java.lang.Boolean.TYPE,
        )
            .apply { isAccessible = true }
        val critical = listOf(
            bracketConstructor.newInstance(0.0, 1.0, false),
            bracketConstructor.newInstance(1.0, 1.25, false),
        )
        val workType = hiddenType("Work")
        val work = workType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val isolate = Aprc::class.java.getDeclaredMethod(
            "isolate",
            List::class.java,
            List::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            workType,
        )
            .apply { isAccessible = true }
        assertThat(isolate.invoke(Aprc, terms, critical, true, false, work)).isNull()
    }

    @Test
    fun `shared work is exhausted after two hundred takes and isolates only with remaining work`() {
        val workType = hiddenType("Work")
        val constructor = workType.getDeclaredConstructor().apply { isAccessible = true }
        val take = workType.getDeclaredMethod("take").apply { isAccessible = true }
        val spent = constructor.newInstance()
        repeat(200) { assertThat(take.invoke(spent)).isEqualTo(true) }
        assertThat(take.invoke(spent)).isEqualTo(false)

        val termType = hiddenType("Term")
        val termConstructor = termType.getDeclaredConstructor(
            BigDecimal::class.java,
            BigDecimal::class.java,
            BigDecimal::class.java,
        )
            .apply { isAccessible = true }
        val terms = listOf(
            termConstructor.newInstance(BigDecimal.ZERO, BigDecimal("-100"), BigDecimal.ZERO),
            termConstructor.newInstance(BigDecimal.ONE, BigDecimal("110"), BigDecimal.ZERO),
        )
        val isolate = Aprc::class.java.getDeclaredMethod(
            "isolate",
            List::class.java,
            List::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            workType,
        )
            .apply { isAccessible = true }
        // Identical positive one-root input: failure with spent work cannot be attributed to
        // a different cashflow, ambiguous root, endpoint, or uncertain numerical neighborhood.
        val fresh = isolate.invoke(Aprc, terms, emptyList<Any>(), true, false, constructor.newInstance()) as? List<*>
        assertThat(fresh).isNotNull()
        assertThat(fresh).hasSize(1)
        assertThat(isolate.invoke(Aprc, terms, emptyList<Any>(), true, false, spent)).isNull()
    }

    @Test
    fun `annual HALF_UP tie and its neighbors have exact permanent oracles`() {
        val cases = listOf(
            Triple("100", "110", "0.100000"),
            Triple("0.010", "0.011", "0.100000"),
            Triple("10000", "11000.004", "0.100000"),
            Triple("10000", "11000.005", "0.100001"),
            Triple("10000", "11000.006", "0.100001"),
        )
        for ((advanceAmount, payment, expected) in cases) {
            // Exact rate=(payment-advance)/advance; these one-year decimal ratios terminate.
            val actual = Aprc.solve(
                listOf(advance(advanceAmount)),
                listOf(Aprc.CashFlow(BigDecimal.ONE, BigDecimal(payment))),
            )
            assertThat(actual).isEqualTo(BigDecimal(expected))
        }
    }

    @Test
    fun `nonannual scaling must not terminate on an absolute currency residual`() {
        for (scale in listOf("1E-10", "1", "1E8")) {
            val factor = BigDecimal(scale)
            // (1+r)^2=121/100=1.21, so the only positive root is exactly0.1.
            // The two-year timing cannot enter the one-year rational fast path.
            val actual = Aprc.solve(
                listOf(Aprc.CashFlow(BigDecimal.ZERO, BigDecimal("100").multiply(factor))),
                listOf(Aprc.CashFlow(BigDecimal("2"), BigDecimal("121").multiply(factor))),
            )
            assertThat(actual).isEqualTo(BigDecimal("0.100000"))
        }
    }
}
