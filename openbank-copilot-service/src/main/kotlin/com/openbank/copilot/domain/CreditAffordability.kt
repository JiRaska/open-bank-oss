// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The L1 advisor's arithmetic (ADR-0269 rule 5).
 *
 * Pure, and deliberately small: given what the customer has (a 360 profile) and what an instalment
 * would cost (a server quote), it says whether it fits and shows the working. It does not decide
 * anything — the credit decision is the deterministic engine's (ADR-0213) — and it does not talk to
 * a model. The model's job is to narrate what this returns, never to compute it (ADR-0089 D4).
 *
 * ## Why the verdict is a type and not prose
 *
 * The whole point of L1 is that it can say no. A free-text answer from a model can be nudged into
 * enthusiasm by a hopeful question; a [Verdict] cannot. The tool returns the verdict AND the
 * numbers behind it, so the narration has nothing to invent and a reviewer can check the advice
 * against arithmetic rather than against tone.
 */
enum class AffordabilityVerdict {
    /** Fits with room left: DSTI under the comfortable threshold and cover intact. */
    COMFORTABLE,

    /** Fits, but it costs the customer their margin. Worth saying out loud, not worth refusing. */
    TIGHT,

    /** Does not fit: it would take DSTI past the limit, or leave under a month of cover. */
    UNAFFORDABLE,

    /** Not enough is known to answer. Never rendered as "yes" — see [CreditAffordability]. */
    UNKNOWN,
}

data class AffordabilityAnswer(
    val verdict: AffordabilityVerdict,
    /** Debt-service-to-income after taking the new instalment, as a fraction. Null when unknown. */
    val dstiAfter: BigDecimal?,
    /** What the customer would have left each month after the new instalment. Null when unknown. */
    val monthlySurplusAfter: BigDecimal?,
    /** Machine-readable reasons, in the order they were decided. Never empty. */
    val reasons: List<String>,
)

object CreditAffordability {

    /** Above this share of income going to debt service, an instalment is refused. */
    private val DSTI_LIMIT = BigDecimal("0.45")

    /** Between this and [DSTI_LIMIT] it fits but is tight — the customer should hear that. */
    private val DSTI_COMFORTABLE = BigDecimal("0.35")

    private val MC = MathContext.DECIMAL64

    /**
     * Answer "can I afford this instalment".
     *
     * [incomeMonthly], [obligationsMonthly] and [netMonthly] come from the 360 profile;
     * [instalment] from a server quote (ADR-0269 rule 4 — never computed here, never by the model).
     *
     * The two rules answer different questions on purpose. DSTI asks "is too much of the income
     * going to debt", which is the regulatory shape; the surplus asks "is there anything left after
     * the customer's actual life", which is the one that decides whether a month goes wrong. A
     * null [netMonthly] simply skips the second — it must not be read as a surplus of zero.
     *
     * A missing or zero income yields [AffordabilityVerdict.UNKNOWN], not a cheerful yes: the
     * customer whose income the bank cannot see is precisely the one an optimistic answer would
     * hurt most.
     */
    fun assess(
        incomeMonthly: BigDecimal?,
        obligationsMonthly: BigDecimal?,
        netMonthly: BigDecimal?,
        instalment: BigDecimal,
    ): AffordabilityAnswer {
        if (incomeMonthly == null || incomeMonthly <= BigDecimal.ZERO) {
            return AffordabilityAnswer(
                AffordabilityVerdict.UNKNOWN,
                null,
                null,
                listOf("INCOME_UNKNOWN"),
            )
        }
        val obligations = obligationsMonthly ?: BigDecimal.ZERO
        val dstiAfter = obligations.add(instalment, MC).divide(incomeMonthly, MC)
        // Surplus is measured against what the customer ACTUALLY has left after everything they
        // spend — the profile's net — not against income minus debt service.
        //
        // The first version of this used income − obligations − instalment, and a test proved that
        // branch could never fire: if debt service is inside the DSTI limit then income minus debt
        // service is necessarily positive, so the surplus rule was dead code dressed as a
        // safeguard. Rent and groceries are exactly what makes it a real second question.
        val surplusAfter = netMonthly?.subtract(instalment, MC)

        val reasons = mutableListOf<String>()
        val verdict = when {
            dstiAfter > DSTI_LIMIT -> {
                reasons += "DSTI_ABOVE_LIMIT"
                AffordabilityVerdict.UNAFFORDABLE
            }
            surplusAfter != null && surplusAfter <= BigDecimal.ZERO -> {
                reasons += "NO_MONTHLY_SURPLUS"
                AffordabilityVerdict.UNAFFORDABLE
            }
            dstiAfter > DSTI_COMFORTABLE -> {
                reasons += "DSTI_TIGHT"
                AffordabilityVerdict.TIGHT
            }
            else -> {
                reasons += "FITS"
                AffordabilityVerdict.COMFORTABLE
            }
        }
        return AffordabilityAnswer(
            verdict = verdict,
            dstiAfter = dstiAfter.setScale(SCALE, RoundingMode.HALF_UP),
            monthlySurplusAfter = surplusAfter?.setScale(2, RoundingMode.HALF_UP),
            reasons = reasons,
        )
    }

    private const val SCALE = 4
}
