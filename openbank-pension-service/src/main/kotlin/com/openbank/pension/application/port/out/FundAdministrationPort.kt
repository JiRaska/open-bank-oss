// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The value of a contract's units at each fund's latest published NAV, in the contract currency. */
data class Valuation(val amount: BigDecimal, val currency: String, val asOf: LocalDate)

/** A redemption order: [reference] identifies it for a reversal; [amount] is what it was placed for. */
data class Redemption(val reference: String, val amount: BigDecimal)

/**
 * pension-fund-service (ADR-0334 §1, slice S4): the unit register behind every contract. The ONE
 * port every pension-service slice uses for it — onboarding/transfers (S2), contributions (S3) and
 * exit (S5) used to declare three overlapping copies, each with its own stub.
 *
 * Orders are FORWARD-PRICED: pension-fund-service accepts them unpriced and settles them at the
 * fund's next published NAV. So a subscription or redemption returns the order reference and the
 * amount it was placed for, never a settled price. Every write is idempotent on [idempotencyKey]
 * (pension-fund-service deduplicates on its `Idempotency-Key`), so a retried activity cannot buy
 * or sell twice.
 */
interface FundAdministrationPort {
    /** Holdings valued at the latest published NAV. Refuses (fails) when a holding has no NAV yet. */
    suspend fun valuation(contractId: UUID, currency: String): Valuation

    /** Buys units for [amount] in the contract's elected strategy; returns the order reference. */
    suspend fun subscribe(contractId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String): String

    /** Sells units worth [amount] at the latest NAV, pro rata across the holdings. */
    suspend fun redeem(contractId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String): Redemption

    /** Compensates [redemption] (a failed transfer-out) by re-buying its amount, idempotently. */
    suspend fun reverseRedemption(contractId: UUID, redemption: Redemption, currency: String)
}
