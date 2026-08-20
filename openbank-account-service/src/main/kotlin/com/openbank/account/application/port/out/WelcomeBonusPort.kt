// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import java.math.BigDecimal
import java.util.UUID

/**
 * Grants the one-time onboarding welcome bonus by initiating an incoming credit to a freshly
 * activated account (ADR-0267). The credit goes through transaction-service as a real, double-entry
 * money-path payment (Dr bank cash-clearing / Cr customer deposit) — never a direct balance poke.
 *
 * Idempotent by construction: the implementation keys the transaction on the account id, so a
 * re-activation or event re-delivery never pays the bonus twice.
 */
interface WelcomeBonusPort {
    suspend fun grantWelcomeBonus(accountId: UUID, amount: BigDecimal, currency: String)
}
