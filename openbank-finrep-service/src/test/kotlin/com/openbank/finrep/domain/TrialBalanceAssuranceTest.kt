// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.domain.model.TrialBalanceAssurance
import com.openbank.finrep.domain.model.TrialBalanceIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The cross-check between finrep's own recomputation and openbank-ledger-service's published
 * verdict (issue #6011).
 *
 * Every test here exists to answer one question: **what input makes the two sides disagree?** A
 * cross-check whose sides cannot disagree is worse than no check — it wears the shape of a control
 * while reporting a constant, which is precisely the defect #5987 and #6010 removed one level down.
 * The disagreement cases are therefore the point of this file, and the agreement cases are only
 * here to prove the disagreement is not reported unconditionally.
 */
class TrialBalanceAssuranceTest {

    /**
     * THE case the issue was filed for: a truncated response that is internally balanced.
     *
     * The full sealed trial balance is the four lines below plus a fifth, `1001 ASSET 70 000`,
     * which does not tie out — so ledger, evaluating `Σ totalDebit == Σ totalCredit` over the full
     * set at the moment it built the response, published `balanced = false`. Something between the
     * two services then dropped lines (a capped or paginated `lines` array, a filtering proxy, a
     * partial deserialisation), and what arrived happens to sum to zero per currency.
     *
     * finrep's own recomputation is therefore SATISFIED — that is the whole difficulty, and why
     * #6010's identity check cannot see this on its own. Only the disagreement can.
     */
    @Test
    fun `a truncated response whose survivors still tie out is caught by the disagreement alone`() {
        val survivors = listOf(
            line("1000", "ASSET", "500000"),
            line("2000", "LIABILITY", "-300000"),
            line("4000", "INCOME", "-260000"),
            line("5000", "EXPENSE", "60000"),
        )
        // Established first, so this test states plainly that the stronger single check passes here.
        assertThat(TrialBalanceIdentity.holds(survivors))
            .describedAs("finrep's own recomputation must PASS, or this test proves nothing")
            .isTrue()

        val assessment = TrialBalanceAssurance.assess(
            TrialBalanceSnapshot(lines = survivors, ledgerReportsBalanced = false),
        )

        assertThat(assessment.verdict).isEqualTo(BalanceVerdict.SOURCES_DISAGREE)
        assertThat(assessment.ownIdentityHolds).isTrue()
        assertThat(assessment.ledgerReportsBalanced).isFalse()
    }

    /**
     * The other direction, and it is not symmetric: offsetting residuals in two currencies.
     *
     * Ledger's verdict is a single scalar over all currencies at once, so +40 000 CZK against
     * −40 000 EUR nets to zero and it publishes `balanced = true`. finrep evaluates per currency
     * (ADR-0025: journals balance within each currency), so neither currency ties out.
     */
    @Test
    fun `offsetting residuals in two currencies disagree with ledger's ungrouped scalar`() {
        val lines = listOf(
            line("1000", "ASSET", "500000"),
            line("2000", "LIABILITY", "-460000"),
            line("1000", "ASSET", "100000", currency = "EUR"),
            line("2000", "LIABILITY", "-140000", currency = "EUR"),
        )
        // The premise: summed across currencies this really is zero, which is what ledger sums.
        assertThat(lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) }).isEqualByComparingTo(BigDecimal.ZERO)

        val assessment = TrialBalanceAssurance.assess(
            TrialBalanceSnapshot(lines = lines, ledgerReportsBalanced = true),
        )

        assertThat(assessment.verdict).isEqualTo(BalanceVerdict.SOURCES_DISAGREE)
        assertThat(assessment.ownIdentityHolds).isFalse()
        assertThat(assessment.residualsByCurrency["CZK"]).isEqualByComparingTo("40000")
        assertThat(assessment.residualsByCurrency["EUR"]).isEqualByComparingTo("-40000")
    }

    /**
     * A dropped line that does NOT leave the survivors balanced is the easy case — both sources
     * object, and finrep's recomputation would have caught it alone. Present so the file does not
     * only contain the hard case, and to pin that this is NOT reported as a disagreement: an
     * accounting defect and an evidence defect have different owners.
     */
    @Test
    fun `both sources objecting is an agreed imbalance, never a disagreement`() {
        val assessment = TrialBalanceAssurance.assess(
            TrialBalanceSnapshot(
                lines = listOf(line("1000", "ASSET", "500000"), line("2000", "LIABILITY", "-410000")),
                ledgerReportsBalanced = false,
            ),
        )

        assertThat(assessment.verdict).isEqualTo(BalanceVerdict.AGREED_IMBALANCED)
    }

    @Test
    fun `both sources agreeing is the only verdict that permits a submission`() {
        val assessment = TrialBalanceAssurance.assess(
            TrialBalanceSnapshot(
                lines = listOf(line("1000", "ASSET", "500000"), line("2000", "LIABILITY", "-500000")),
                ledgerReportsBalanced = true,
            ),
        )

        assertThat(assessment.verdict).isEqualTo(BalanceVerdict.AGREED_BALANCED)
    }

    /**
     * An ABSENT verdict is a third state, not `false`. Ledger declares `balanced` unconditionally
     * and the committed pact pins it, so absence means the response is not the response this
     * service contracted for — a shape problem, which must never be reported as "the bank's books
     * do not balance", and must never be permitted to pass as a submission either.
     */
    @Test
    fun `an absent ledger verdict is its own state, distinguishable from a false one`() {
        val balancedLines = listOf(line("1000", "ASSET", "500000"), line("2000", "LIABILITY", "-500000"))

        val absent = TrialBalanceAssurance.assess(
            TrialBalanceSnapshot(lines = balancedLines, ledgerReportsBalanced = null),
        )
        val explicitlyFalse = TrialBalanceAssurance.assess(
            TrialBalanceSnapshot(lines = balancedLines, ledgerReportsBalanced = false),
        )

        assertThat(absent.verdict).isEqualTo(BalanceVerdict.LEDGER_FLAG_ABSENT)
        assertThat(absent.ledgerReportsBalanced).isNull()
        // The discriminating assertion: the two must not collapse onto one value. With `balanced`
        // deserialised as a non-null Boolean, jackson-module-kotlin coerces an absent field to
        // `false` and these two inputs become indistinguishable at this layer.
        assertThat(absent.verdict).isNotEqualTo(explicitlyFalse.verdict)
        assertThat(explicitlyFalse.verdict).isEqualTo(BalanceVerdict.SOURCES_DISAGREE)
    }

    /** Every verdict must be reachable, or the enum is documentation rather than a measurement. */
    @Test
    fun `all four verdicts are reachable from real snapshots`() {
        val balanced = listOf(line("1000", "ASSET", "1"), line("2000", "LIABILITY", "-1"))
        val imbalanced = listOf(line("1000", "ASSET", "1"))
        val reached = listOf(
            TrialBalanceSnapshot(balanced, true),
            TrialBalanceSnapshot(imbalanced, false),
            TrialBalanceSnapshot(imbalanced, true),
            TrialBalanceSnapshot(balanced, null),
        ).map { TrialBalanceAssurance.assess(it).verdict }

        assertThat(reached).containsExactlyInAnyOrderElementsOf(BalanceVerdict.entries)
    }

    private fun line(code: String, accountType: String, net: String, currency: String = "CZK") =
        TrialBalanceLineDto(code = code, accountType = accountType, net = BigDecimal(net), currency = currency)
}
