// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.rules

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The money thresholds the amount-based fraud rules compare against, as one versioned set
 * (ADR-0347). Per rule, per currency.
 *
 * These figures are business reference data owned by the risk function, not code: they change
 * when calibration changes, and each change is a four-eyes activation of a new version
 * ([com.openbank.fraud.application.usecase.FraudThresholdActivationService]), never a release. The
 * rule LOGIC — which comparisons exist, what they score, what they return — stays code (ADR-0084
 * §3); only the numbers moved.
 *
 * Closed vocabulary (the ADR-0138/0212 invariant): [ruleKeys] is exactly [RULE_KEYS], every
 * currency is an ISO 4217 code, and every amount is positive with at most [MAX_SCALE] decimals
 * (the `fraud_scores.amount` column is `NUMERIC(20,4)`). A set that violates any of these does not
 * construct, so an invalid set can never reach the engine.
 *
 * A currency ABSENT from a rule's map is legal and deliberate: that rule then fails closed for that
 * currency (REVIEW, `…-unmapped-currency`), exactly as the code constants did before this set
 * existed. Absence is how "we have not calibrated GBP" is expressed — there is no wildcard.
 */
data class FraudThresholdSet(val version: Int, val thresholds: Map<String, Map<String, BigDecimal>>) {

    init {
        require(version >= 1) { "threshold set version must be >= 1, was $version" }
        require(thresholds.keys == RULE_KEYS) {
            "threshold set must name exactly the rules ${RULE_KEYS.sorted()}, got ${thresholds.keys.sorted()}"
        }
        thresholds.forEach { (rule, byCurrency) ->
            byCurrency.forEach { (currency, amount) ->
                require(currency.matches(ISO_4217)) { "rule '$rule': '$currency' is not a 3-letter ISO 4217 code" }
                require(amount.signum() > 0) { "rule '$rule' $currency: threshold must be positive, was $amount" }
                // Bound the integer digits too: `1E+100000` passes a scale check and would expand into
                // a 100 000-digit string in contentHash / the JSON payload (NUMERIC(20,4) holds 16).
                require(amount.precision() - amount.scale() <= MAX_INTEGER_DIGITS) {
                    "rule '$rule' $currency: at most $MAX_INTEGER_DIGITS integer digits, was $amount"
                }
                require(amount.stripTrailingZeros().scale() <= MAX_SCALE) {
                    "rule '$rule' $currency: at most $MAX_SCALE decimal places, was $amount"
                }
            }
        }
    }

    /** The threshold for [ruleKey] in [currency], or null when this set does not map that currency. */
    fun thresholdFor(ruleKey: String, currency: String): BigDecimal? = thresholds[ruleKey]?.get(currency)

    /**
     * SHA-256 over a canonical rendering of the CONTENT (not the version). Two proposals with the
     * same figures hash equal whatever their map order or trailing zeros, which is what lets a
     * retried propose replay instead of stacking a duplicate.
     *
     * The `|` / newline join is injective here only because every component is validated above to
     * contain neither: rule keys are a closed set of kebab-case literals, currencies are `[A-Z]{3}`,
     * amounts are plain decimals.
     */
    val contentHash: String
        get() {
            val canonical = thresholds.toSortedMap().flatMap { (rule, byCurrency) ->
                byCurrency.toSortedMap().map { (ccy, amount) ->
                    "$rule|$ccy|${amount.stripTrailingZeros().toPlainString()}"
                }
            }.joinToString("\n")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

    companion object {
        /** [LargeSingleTransactionReviewRule]. */
        const val LARGE_SINGLE_TRANSACTION = "large-single-transaction"

        /** [VelocityH1HighValueReviewRule]. */
        const val VELOCITY_H1_AMOUNT = "velocity-h1-amount"

        /** [NewPayeeHighAmountReviewRule]. */
        const val NEW_PAYEE_HIGH_AMOUNT = "new-payee-high-amount"

        /** Every rule that reads a money threshold. Adding one is a code change, by design. */
        val RULE_KEYS: Set<String> = setOf(LARGE_SINGLE_TRANSACTION, VELOCITY_H1_AMOUNT, NEW_PAYEE_HIGH_AMOUNT)

        const val MAX_SCALE = 4

        const val MAX_INTEGER_DIGITS = 16

        private val ISO_4217 = Regex("^[A-Z]{3}$")
    }
}
