// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.port.`in`.NostroReconciliationUseCase
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.NostroStatementRepository
import com.openbank.treasury.application.usecase.NostroReconciliationService
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.infrastructure.nostro.NostroConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Clock

/** The use case is a plain class (no CDI annotation in the application layer); wired here. */
@ApplicationScoped
class TreasuryServiceProducer {
    @Produces
    @ApplicationScoped
    fun treasuryDealUseCase(
        deals: DealRepository,
        counterparties: CounterpartyRepository,
        ledger: LedgerPostingPort,
        objectMapper: ObjectMapper,
        clock: Clock,
        @ConfigProperty(name = "openbank.treasury.fx-spot.rate-check.enabled", defaultValue = "false")
        rateCheckEnabled: Boolean,
        @ConfigProperty(name = "openbank.treasury.fx-spot.rate-check.tolerance-percent", defaultValue = "2.0")
        tolerancePercent: BigDecimal,
        // ADR-0315 D2: settlement needs a CONFIRMED deal. `false` only for a deployment without a
        // confirmation step: it lets BOOKED settle directly, the pre-CONFIRMED behaviour.
        @ConfigProperty(name = "openbank.treasury.confirmation.required", defaultValue = "true")
        confirmationRequired: Boolean,
    ): TreasuryDealUseCase = TreasuryDealService(
        deals,
        counterparties,
        ledger,
        objectMapper,
        clock,
        // #10896: treasury has no fx-service client yet, so there is no mid to compare with. Enabling
        // the check without one flags EVERY FX spot deal "mid unavailable" — loud, not silent.
        FxMidRatePort.NONE,
        FxRateTolerance(rateCheckEnabled, tolerancePercent),
        confirmationRequired,
    )

    @Produces
    @ApplicationScoped
    fun nostroReconciliationUseCase(
        statements: NostroStatementRepository,
        ledger: LedgerReadPort,
        config: NostroConfig,
        clock: Clock,
    ): NostroReconciliationUseCase = NostroReconciliationService(statements, ledger, config.accounts(), clock)
}
