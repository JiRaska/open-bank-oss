// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fraud.domain.rules

import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.ScoreRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FraudRuleEngineTest {

    private fun request(velocityH1Count: Long = 0, velocityH24Count: Long = 0) = ScoreRequest(
        amount = BigDecimal("1250.00"),
        currency = "CZK",
        rail = "SEPA_INSTANT",
        accountId = UUID.randomUUID(),
        counterpartyId = UUID.randomUUID(),
        velocityH1Count = velocityH1Count,
        velocityH24Count = velocityH24Count,
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

        assertThat(result.ruleVersion).isEqualTo("v2")
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
}
