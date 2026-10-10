// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.port.`in`.NostroBreakUseCase
import com.openbank.treasury.application.port.`in`.NostroReconciliationUseCase
import com.openbank.treasury.application.port.`in`.PortfolioStatementUseCase
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.`in`.TreasuryQuoteUseCase
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.CurveSetPort
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.application.port.out.NostroStatementRepository
import com.openbank.treasury.application.port.out.PortfolioStatementRepository
import com.openbank.treasury.application.usecase.NostroBreakService
import com.openbank.treasury.application.usecase.NostroReconciliationService
import com.openbank.treasury.application.usecase.PortfolioStatementService
import com.openbank.treasury.application.usecase.SimulatedQuoteService
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.CfiClassMapping
import com.openbank.treasury.infrastructure.nostro.NostroConfig
import com.openbank.treasury.infrastructure.portfolio.PortfolioConfig
import com.openbank.treasury.infrastructure.quote.SimulatedQuoteConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Clock

/** The use case is a plain class (no CDI annotation in the application layer); wired here. */
@ApplicationScoped
class TreasuryServiceProducer {
    /**
     * ADR-0315 D9: the simulated counterparties' quotes. Enabled only when the simulated market
     * itself is on AND `quotes.enabled` — quotes of invented counterparties have no business in an
     * environment with a real market. The bean is also the [TreasuryQuoteUseCase] the resource injects.
     */
    @Produces
    @ApplicationScoped
    fun simulatedQuoteService(
        curves: CurveSetPort,
        counterparties: CounterpartyRepository,
        config: SimulatedQuoteConfig,
        @ConfigProperty(name = "openbank.treasury.simulated-market.enabled", defaultValue = "false")
        marketEnabled: Boolean,
    ): SimulatedQuoteService =
        SimulatedQuoteService(curves, counterparties, config.spreadBp(), marketEnabled && config.enabled())

    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList") // one CDI producer wiring every collaborator of the use case
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
        quotes: SimulatedQuoteService,
        productLimits: ProductLimitConfig,
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
        quotes,
        productLimits.toPolicy(),
    )

    @Produces
    @ApplicationScoped
    fun nostroReconciliationUseCase(
        statements: NostroStatementRepository,
        ledger: LedgerReadPort,
        config: NostroConfig,
        clock: Clock,
    ): NostroReconciliationUseCase = NostroReconciliationService(statements, ledger, config.accounts(), clock)

    /** ADR-0337 amendment: the custodian's period-end statement of holdings (`openbank.treasury.portfolio`). */
    @Produces
    @ApplicationScoped
    fun portfolioStatementUseCase(
        statements: PortfolioStatementRepository,
        config: PortfolioConfig,
        clock: Clock,
    ): PortfolioStatementUseCase = PortfolioStatementService(
        statements = statements,
        entity = config.entity().orElse(null)?.takeIf { it.isNotBlank() },
        safekeepingAccounts = config.safekeepingAccounts().orElse(emptyList()),
        classes = CfiClassMapping(config.cfiClasses()),
        clock = clock,
    )

    /** ADR-0315 D7: breaks with age and the alert threshold (`openbank.treasury.nostro.break-alert-*`). */
    @Produces
    @ApplicationScoped
    fun nostroBreakUseCase(
        statements: NostroStatementRepository,
        reconciliation: NostroReconciliationUseCase,
        breaks: NostroBreakRepository,
        config: NostroConfig,
        objectMapper: ObjectMapper,
        clock: Clock,
    ): NostroBreakUseCase = NostroBreakService(
        statements,
        reconciliation,
        breaks,
        config.accounts(),
        BreakAlertPolicy(config.breakAlertAgeDays(), config.breakAlertMinAmount()),
        objectMapper,
        clock,
    )
}
