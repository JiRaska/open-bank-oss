// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application.usecase

import com.openbank.loyalty.application.port.out.LeafLedgerRepository
import com.openbank.loyalty.application.port.out.LoyaltyMetricsPort
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/**
 * ADR-0282 D5's provisioning input. Lístky outstanding are an obligation of the bank under
 * IFRS 15, and this use case is the number that obligation is measured by.
 *
 * It computes and publishes; it does **not** post. `openbank-billing-service` owns the balanced
 * journal into `openbank-ledger-service`, because that service is money-path and this one is not.
 * Keeping the split here is what lets this slice ship without an ADR-0030 threat model, and it is
 * also the honest boundary: a loyalty service that posted its own journals would be a second
 * ledger.
 */
@ApplicationScoped
class ProvisioningSummaryUseCase(
    private val ledger: LeafLedgerRepository,
    private val metrics: LoyaltyMetricsPort,
    private val clock: Clock,
) {
    suspend fun summarise(): ProvisioningSummary {
        val at = Instant.now(clock)
        val outstanding = ledger.outstandingLeaves(at)
        metrics.outstandingObligation(outstanding)
        return ProvisioningSummary(at = at, outstandingLeaves = outstanding)
    }

    data class ProvisioningSummary(val at: Instant, val outstandingLeaves: Long)
}
