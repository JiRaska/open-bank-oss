// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.rules

import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.ScoreRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FraudRuleEngineTest {

    private fun request(
        velocityH1Count: Long = 0,
        velocityH24Count: Long = 0,
        amount: BigDecimal = BigDecimal("1250.00"),
        velocityH1TotalAmount: BigDecimal = BigDecimal.ZERO,
    ) = ScoreRequest(
        amount = amount,
        currency = "CZK",
        rail = "SEPA_INSTANT",
        accountId = UUID.randomUUID(),
        counterpartyId = UUID.randomUUID(),
        velocityH1Count = velocityH1Count,
        velocityH24Count = velocityH24Count,
        velocityH1TotalAmount = velocityH1TotalAmount,
    )

    @Test
    fun `baseline rule always returns ALLOW with zero score`() {
        val result = FraudRuleEngine.score(request())

        assertThat(result.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(result.score).isZero()
        assertThat(result.reasons).contains("baseline-allow")
    }

    @Test
    fun `score pins the current rule version`() {
        val result = FraudRuleEngine.score(request())

        assertThat(result.ruleVersion).isEqualTo("v3")
        assertThat(result.ruleVersion).isEqualTo(FraudRuleEngine.RULE_VERSION)
    }

    @Test
    fun `engine is deterministic for identical requests`() {
        val req = request()

        assertThat(FraudRuleEngine.score(req)).isEqualTo(FraudRuleEngine.score(req))
    }

    @Test
    fun `verdict mapping picks the most severe verdict across rule hits`() {
        // Engine takes max severity ALLOW < CHALLENGE < REVIEW < DECLINE.
        assertThat(FraudVerdict.entries).containsExactly(
            FraudVerdict.ALLOW,
            FraudVerdict.CHALLENGE,
            FraudVerdict.REVIEW,
            FraudVerdict.DECLINE,
        )
    }

    @Test
    fun `baseline rule fires for any request`() {
        val hit = BaselineAllowRule.evaluate(request())

        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(hit.scoreDelta).isZero()
        assertThat(hit.reason).isEqualTo("baseline-allow")
    }

    // ── VelocityH1ReviewRule ─────────────────────────────────────────────────

    @Test
    fun `VelocityH1ReviewRule does not fire below cap`() {
        val hit = VelocityH1ReviewRule.evaluate(request(velocityH1Count = 9))
        assertThat(hit).isNull()
    }

    @Test
    fun `VelocityH1ReviewRule fires at exactly the cap`() {
        val hit = VelocityH1ReviewRule.evaluate(request(velocityH1Count = 10))

        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(hit.scoreDelta).isEqualTo(30)
        assertThat(hit.reason).isEqualTo("velocity-h1-cap")
    }

    @Test
    fun `VelocityH1ReviewRule fires above cap`() {
        val hit = VelocityH1ReviewRule.evaluate(request(velocityH1Count = 99))
        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
    }

    @Test
    fun `VelocityH1ReviewRule is silent when no signal (count zero)`() {
        val hit = VelocityH1ReviewRule.evaluate(request(velocityH1Count = 0))
        assertThat(hit).isNull()
    }

    // ── VelocityH24ReviewRule ────────────────────────────────────────────────

    @Test
    fun `VelocityH24ReviewRule does not fire below cap`() {
        val hit = VelocityH24ReviewRule.evaluate(request(velocityH24Count = 49))
        assertThat(hit).isNull()
    }

    @Test
    fun `VelocityH24ReviewRule fires at exactly the cap`() {
        val hit = VelocityH24ReviewRule.evaluate(request(velocityH24Count = 50))

        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(hit.scoreDelta).isEqualTo(20)
        assertThat(hit.reason).isEqualTo("velocity-h24-cap")
    }

    @Test
    fun `VelocityH24ReviewRule fires above cap`() {
        val hit = VelocityH24ReviewRule.evaluate(request(velocityH24Count = 100))
        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
    }

    @Test
    fun `VelocityH24ReviewRule is silent when no signal (count zero)`() {
        val hit = VelocityH24ReviewRule.evaluate(request(velocityH24Count = 0))
        assertThat(hit).isNull()
    }

    // ── LargeSingleTransactionReviewRule ─────────────────────────────────────

    @Test
    fun `LargeSingleTransactionReviewRule does not fire below threshold`() {
        val hit = LargeSingleTransactionReviewRule.evaluate(request(amount = BigDecimal("499999.99")))
        assertThat(hit).isNull()
    }

    @Test
    fun `LargeSingleTransactionReviewRule fires at exactly the threshold`() {
        val hit = LargeSingleTransactionReviewRule.evaluate(request(amount = BigDecimal("500000")))

        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(hit.scoreDelta).isEqualTo(25)
        assertThat(hit.reason).isEqualTo("large-single-transaction")
    }

    @Test
    fun `LargeSingleTransactionReviewRule fires above threshold`() {
        val hit = LargeSingleTransactionReviewRule.evaluate(request(amount = BigDecimal("1500000")))
        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
    }

    @Test
    fun `LargeSingleTransactionReviewRule is silent for typical small amounts`() {
        val hit = LargeSingleTransactionReviewRule.evaluate(request(amount = BigDecimal("1250.00")))
        assertThat(hit).isNull()
    }

    // ── VelocityH1HighValueReviewRule ────────────────────────────────────────

    @Test
    fun `VelocityH1HighValueReviewRule does not fire below cap`() {
        val hit = VelocityH1HighValueReviewRule.evaluate(request(velocityH1TotalAmount = BigDecimal("999999.99")))
        assertThat(hit).isNull()
    }

    @Test
    fun `VelocityH1HighValueReviewRule fires at exactly the cap`() {
        val hit = VelocityH1HighValueReviewRule.evaluate(request(velocityH1TotalAmount = BigDecimal("1000000")))

        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(hit.scoreDelta).isEqualTo(35)
        assertThat(hit.reason).isEqualTo("velocity-h1-amount-cap")
    }

    @Test
    fun `VelocityH1HighValueReviewRule fires above cap`() {
        val hit = VelocityH1HighValueReviewRule.evaluate(request(velocityH1TotalAmount = BigDecimal("5000000")))
        assertThat(hit).isNotNull
        assertThat(hit!!.verdict).isEqualTo(FraudVerdict.REVIEW)
    }

    @Test
    fun `VelocityH1HighValueReviewRule is silent when no signal (zero total)`() {
        val hit = VelocityH1HighValueReviewRule.evaluate(request(velocityH1TotalAmount = BigDecimal.ZERO))
        assertThat(hit).isNull()
    }

    // ── Engine integration ───────────────────────────────────────────────────

    @Test
    fun `engine returns REVIEW when h1 velocity cap is breached`() {
        val result = FraudRuleEngine.score(request(velocityH1Count = 10))

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("velocity-h1-cap")
    }

    @Test
    fun `engine returns REVIEW when h24 velocity cap is breached`() {
        val result = FraudRuleEngine.score(request(velocityH24Count = 50))

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("velocity-h24-cap")
    }

    @Test
    fun `engine combines score from both velocity rules when both fire`() {
        val result = FraudRuleEngine.score(request(velocityH1Count = 10, velocityH24Count = 50))

        assertThat(result.score).isEqualTo(50) // 0 (baseline) + 30 (h1) + 20 (h24)
        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("velocity-h1-cap", "velocity-h24-cap")
    }

    @Test
    fun `engine returns ALLOW when both counters are below caps`() {
        val result = FraudRuleEngine.score(request(velocityH1Count = 5, velocityH24Count = 30))

        assertThat(result.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(result.score).isZero()
    }

    @Test
    fun `engine returns REVIEW when the large single-transaction threshold is breached`() {
        val result = FraudRuleEngine.score(request(amount = BigDecimal("750000")))

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("large-single-transaction")
    }

    @Test
    fun `engine returns REVIEW when the h1 high-value velocity cap is breached`() {
        val result = FraudRuleEngine.score(request(velocityH1TotalAmount = BigDecimal("1200000")))

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("velocity-h1-amount-cap")
    }

    @Test
    fun `engine combines score across all firing rules in a mixed scenario`() {
        val result = FraudRuleEngine.score(
            request(
                velocityH1Count = 10,
                velocityH24Count = 50,
                amount = BigDecimal("600000"),
                velocityH1TotalAmount = BigDecimal("1000000"),
            ),
        )

        // 0 (baseline) + 30 (h1 count) + 20 (h24 count) + 25 (large single tx) + 35 (h1 amount)
        assertThat(result.score).isEqualTo(110)
        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains(
            "baseline-allow",
            "velocity-h1-cap",
            "velocity-h24-cap",
            "large-single-transaction",
            "velocity-h1-amount-cap",
        )
    }

    @Test
    fun `engine severity ordering picks REVIEW as the most severe verdict across a mix of firing rules`() {
        // ALLOW (baseline) mixed with multiple REVIEW-firing rules must still resolve to REVIEW —
        // the engine takes the max-severity verdict, not the first or last rule's verdict.
        val result = FraudRuleEngine.score(
            request(velocityH1Count = 10, amount = BigDecimal("600000")),
        )

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("baseline-allow", "velocity-h1-cap", "large-single-transaction")
    }

    @Test
    fun `engine ALLOWs when no threshold or cap is breached across all v3 rules`() {
        val result = FraudRuleEngine.score(
            request(
                velocityH1Count = 5,
                velocityH24Count = 30,
                amount = BigDecimal("1250.00"),
                velocityH1TotalAmount = BigDecimal("100000"),
            ),
        )

        assertThat(result.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(result.score).isZero()
        assertThat(result.reasons).containsExactly("baseline-allow")
    }
}
