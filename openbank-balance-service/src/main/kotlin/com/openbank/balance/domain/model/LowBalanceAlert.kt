// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Customer-selected threshold for one account pocket. Both values use the pocket currency. */
data class LowBalanceAlertRule(val threshold: BigDecimal, val rearmMargin: BigDecimal, val cooldown: Duration) {
    init {
        require(threshold.signum() >= 0) { "threshold must be non-negative" }
        require(rearmMargin.signum() > 0) { "rearm margin must be positive" }
        require(!cooldown.isNegative && !cooldown.isZero) { "cooldown must be positive" }
    }
}

data class LowBalanceAlertState(val armed: Boolean, val generation: Long, val lastAlertAt: Instant?) {
    companion object {
        /** Opting in while already below the threshold cannot manufacture a downward crossing. */
        fun initial(currentAvailable: BigDecimal, rule: LowBalanceAlertRule): LowBalanceAlertState =
            LowBalanceAlertState(currentAvailable >= rule.threshold, 0, null)
    }
}

data class LowBalanceAlertDecision(val state: LowBalanceAlertState, val emit: Boolean)

/**
 * Apply the latest authoritative spendable balance to durable alert state. A technical event is
 * only a wake-up: its carried amount is never the decision input, so late and replayed events
 * cannot cause a historical threshold alert. The caller must serialise transitions per pocket.
 */
fun LowBalanceAlertState.evaluate(
    currentAvailable: BigDecimal,
    rule: LowBalanceAlertRule,
    now: Instant,
): LowBalanceAlertDecision {
    if (!armed) {
        val rearmed = currentAvailable >= rule.threshold + rule.rearmMargin
        return LowBalanceAlertDecision(if (rearmed) copy(armed = true) else this, false)
    }
    if (currentAvailable >= rule.threshold) return LowBalanceAlertDecision(this, false)
    val inCooldown = lastAlertAt?.let { now.isBefore(it.plus(rule.cooldown)) } ?: false
    if (inCooldown) return LowBalanceAlertDecision(copy(armed = false), false)
    return LowBalanceAlertDecision(copy(armed = false, generation = generation + 1, lastAlertAt = now), true)
}
