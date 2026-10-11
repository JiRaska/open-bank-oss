// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class FundStatus { ACTIVE, CLOSED }

/**
 * A segregated pension fund (ADR-0334 §1).
 *
 * The fund's assets belong to its participants, not to the bank: they are held under
 * [custodyAccountReference] at the [depositaryReference] and are never posted into treasury or the
 * bank ledger as bank positions. Nothing in this aggregate references a bank GL account, on purpose.
 */
data class Fund(
    val id: UUID,
    val name: String,
    val isin: String,
    val lei: String,
    val depositaryReference: String,
    val custodyAccountReference: String,
    val currency: String,
    /** PRIIPs summary risk indicator, 1 (lowest) .. 7. */
    val riskClass: Int,
    /** The fund a participant's savings must sit in when the jurisdiction pack requires a conservative option. */
    val mandatoryConservative: Boolean,
    /** Annual management fee as a fraction of net assets, e.g. 0.008 for 0.8 %. */
    val managementFeeRate: BigDecimal,
    /** NAV per unit used for the very first issue, while no units are outstanding. */
    val launchNavPerUnit: BigDecimal,
    val status: FundStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "fund name must not be blank" }
        require(ISIN.matches(isin)) { "isin must be 12 characters: 2-letter country, 9 alphanumerics, 1 check digit" }
        require(LEI.matches(lei)) { "lei must be 20 characters: 18 alphanumerics and 2 check digits" }
        require(depositaryReference.isNotBlank()) { "a segregated fund requires a depositary reference" }
        require(custodyAccountReference.isNotBlank()) { "a segregated fund requires a custody account reference" }
        require(CURRENCY.matches(currency)) { "currency must be an ISO 4217 code" }
        require(riskClass in MIN_RISK_CLASS..MAX_RISK_CLASS) { "riskClass must be within 1..7" }
        require(managementFeeRate.signum() >= 0 && managementFeeRate < BigDecimal.ONE) {
            "managementFeeRate must be a fraction in [0, 1)"
        }
        require(launchNavPerUnit.signum() > 0) { "launchNavPerUnit must be positive" }
    }

    fun amend(
        name: String,
        depositaryReference: String,
        custodyAccountReference: String,
        riskClass: Int,
        mandatoryConservative: Boolean,
        managementFeeRate: BigDecimal,
        now: Instant,
    ): Fund {
        check(status == FundStatus.ACTIVE) { "fund $id is closed" }
        return copy(
            name = name,
            depositaryReference = depositaryReference,
            custodyAccountReference = custodyAccountReference,
            riskClass = riskClass,
            mandatoryConservative = mandatoryConservative,
            managementFeeRate = managementFeeRate,
            updatedAt = now,
        )
    }

    fun close(now: Instant): Fund {
        check(status == FundStatus.ACTIVE) { "fund $id is already closed" }
        return copy(status = FundStatus.CLOSED, updatedAt = now)
    }

    private companion object {
        const val MIN_RISK_CLASS = 1
        const val MAX_RISK_CLASS = 7
        val ISIN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")
        val LEI = Regex("^[A-Z0-9]{18}[0-9]{2}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}
