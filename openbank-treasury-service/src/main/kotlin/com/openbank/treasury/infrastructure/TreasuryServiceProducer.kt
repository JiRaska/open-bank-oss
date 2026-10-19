// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.port.`in`.CashFlowUseCase
import com.openbank.treasury.application.port.`in`.NostroBreakUseCase
import com.openbank.treasury.application.port.`in`.NostroReconciliationUseCase
import com.openbank.treasury.application.port.`in`.PortfolioStatementUseCase
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.`in`.TreasuryQuoteUseCase
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.CurveSetPort
import com.openbank.treasury.application.port.out.DealReferenceLookup
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.application.port.out.NostroStatementRepository
import com.openbank.treasury.application.port.out.PortfolioStatementRepository
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.usecase.BookCategories
import com.openbank.treasury.application.usecase.CashFlowService
import com.openbank.treasury.application.usecase.NostroBreakService
import com.openbank.treasury.application.usecase.NostroReconciliationService
import com.openbank.treasury.application.usecase.PortfolioStatementService
import com.openbank.treasury.application.usecase.ProductMandate
import com.openbank.treasury.application.usecase.ReferenceDataService
import com.openbank.treasury.application.usecase.ScheduleService
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
@Suppress("TooManyFunctions") // one producer per use case
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
        schedules: ScheduleService,
        references: ReferenceRepository,
        cashFlows: CashFlowRepository,
        payments: com.openbank.treasury.application.usecase.PaymentService,
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
        schedules,
        ProductMandate(references, productLimits.toPolicy(), clock)::policy,
        cashFlows,
        BookCategories(references, clock)::of,
        paymentGuard = { id -> payments.guardDealChange(id) },
    )

    /** ADR-0344 D3/D4: reference validation and schedule generation through openbank-libs-finance. */
    @Produces
    @ApplicationScoped
    fun scheduleService(references: ReferenceRepository, cashFlows: CashFlowRepository, clock: Clock): ScheduleService =
        ScheduleService(references, cashFlows, clock)

    /** ADR-0344 D5: the fixing workflow and its four-eyes market data. */
    @Produces
    @ApplicationScoped
    fun fixingService(
        flows: com.openbank.treasury.application.port.out.FixingFlowRepository,
        deals: DealRepository,
        schedules: ScheduleService,
        marketData: com.openbank.treasury.application.port.out.MarketDataRepository,
        dayCompletion: com.openbank.treasury.application.port.out.DayCompletionRepository,
        objectMapper: ObjectMapper,
        clock: Clock,
    ): com.openbank.treasury.application.usecase.FixingService =
        com.openbank.treasury.application.usecase.FixingService(
            flows,
            deals,
            schedules,
            marketData,
            dayCompletion,
            objectMapper,
            clock,
        )

    /** ADR-0344 D5: no feed ingests the interbank indices yet; the port is where one plugs in. */
    @Produces
    @ApplicationScoped
    fun indexFeed(): com.openbank.treasury.application.port.out.IndexFeed =
        com.openbank.treasury.application.port.out.IndexFeed { emptyList() }

    @Produces
    @ApplicationScoped
    fun cashFlowUseCase(
        deals: DealRepository,
        cashFlows: CashFlowRepository,
        schedules: ScheduleService,
        objectMapper: ObjectMapper,
        clock: Clock,
    ): CashFlowUseCase = CashFlowService(deals, cashFlows, schedules, objectMapper, clock)

    /** ADR-0344 D3: the reference-data steward; the plain service is also what deal capture validates against. */
    @Produces
    @ApplicationScoped
    fun referenceDataService(
        records: ReferenceRepository,
        deals: DealReferenceLookup,
        clock: Clock,
    ): ReferenceDataService = ReferenceDataService(records, deals, clock)

    @Produces
    @ApplicationScoped
    fun nostroReconciliationUseCase(
        statements: NostroStatementRepository,
        ledger: LedgerReadPort,
        config: NostroConfig,
        clock: Clock,
        payments: com.openbank.treasury.application.usecase.PaymentService,
    ): NostroReconciliationUseCase = NostroReconciliationService(statements, ledger, config.accounts(), clock) {
            ccy,
            amount,
            date,
            ref,
            who,
        ->
        payments.matchIncoming(ccy, amount, date, ref, who)
    }

    /** ADR-0344 D6: the deal-to-payment chain. */
    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun paymentService(
        payments: com.openbank.treasury.application.port.out.PaymentRepository,
        deals: DealRepository,
        cashFlows: CashFlowRepository,
        references: ReferenceRepository,
        ledger: LedgerReadPort,
        dayCompletion: com.openbank.treasury.application.port.out.DayCompletionRepository,
        objectMapper: ObjectMapper,
        clock: Clock,
        meterRegistry: io.micrometer.core.instrument.MeterRegistry,
        @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "openbank.treasury.payments.cut-off-window-days",
            defaultValue = "2",
        )
        window: Long,
        @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "openbank.treasury.payments.own-bic",
            defaultValue = com.openbank.treasury.application.usecase.PaymentService.DEFAULT_OWN_BIC,
        )
        ownBic: String,
        counterparties: CounterpartyRepository,
    ): com.openbank.treasury.application.usecase.PaymentService =
        com.openbank.treasury.application.usecase.PaymentService(
            payments,
            deals,
            cashFlows,
            references,
            ledger,
            dayCompletion,
            objectMapper,
            clock,
            window,
            onFundingExceeded = { ccy ->
                meterRegistry.counter("openbank.treasury.funding.exceeded", "currency", ccy).increment()
            },
            onParkedMatch = { meterRegistry.counter("openbank.treasury.payments.match.parked").increment() },
            ownBic = ownBic,
            counterparty = { id ->
                counterparties.findById(id)?.let {
                    com.openbank.treasury.application.usecase.CounterpartyIdentity(it.name, it.synthetic)
                }
            },
        )

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
