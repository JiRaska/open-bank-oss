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
        meters: io.micrometer.core.instrument.MeterRegistry,
        intents: com.openbank.treasury.application.port.out.PostingIntentPort,
        @ConfigProperty(name = "openbank.treasury.posting-intent.max-resume-age", defaultValue = "PT4H")
        maxResumeAge: java.time.Duration,
        @ConfigProperty(name = "openbank.treasury.posting-intent.grace", defaultValue = "PT2M")
        intentGrace: java.time.Duration,
        @ConfigProperty(name = "openbank.treasury.posting-intent.claim-batch", defaultValue = "100")
        claimBatch: Int,
        @ConfigProperty(name = "quarkus.rest-client.ledger-service.connect-timeout", defaultValue = "2000")
        ledgerConnectMs: Long,
        @ConfigProperty(name = "quarkus.rest-client.ledger-service.read-timeout", defaultValue = "5000")
        ledgerReadMs: Long,
    ): TreasuryDealUseCase {
        // #13883: an intent older than the grace counts as having no attempt in flight (a coded ledger
        // refusal then proves absence; an operator may close it). That holds only while one ledger
        // call, connect plus read (the client has no retry), ends well inside the grace.
        requireGraceAboveLedgerTimeout(intentGrace, ledgerConnectMs, ledgerReadMs)
        return TreasuryDealService(
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
            // ADR-0342 (#13882): a journal first recorded for a day that has already ended.
            { event -> meters.counter("openbank.treasury.close.late_journals", "event", event.name).increment() },
            // #13883: settle, mature and reverse record a posting intent before contacting the ledger.
            intents,
            maxResumeAge,
            intentGrace,
            claimBatch,
        )
    }

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
        objectMapper: ObjectMapper,
        clock: Clock,
    ): com.openbank.treasury.application.usecase.FixingService =
        com.openbank.treasury.application.usecase.FixingService(
            flows,
            deals,
            schedules,
            marketData,
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
        classes = CfiClassMapping(config.cfiClasses(), config.debtClassesByIsin()),
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

/**
 * #13883: the posting-intent grace must exceed one whole ledger call by a wide margin, or an intent
 * past the grace could still have its own request in flight. Refuses to start otherwise.
 */
internal fun requireGraceAboveLedgerTimeout(grace: java.time.Duration, connectMs: Long, readMs: Long) {
    val call = java.time.Duration.ofMillis(connectMs + readMs)
    require(grace >= call.multipliedBy(GRACE_TO_LEDGER_CALL_FACTOR)) {
        "openbank.treasury.posting-intent.grace ($grace) must be at least $GRACE_TO_LEDGER_CALL_FACTOR x the " +
            "ledger client's connect + read timeout ($call)"
    }
}

private const val GRACE_TO_LEDGER_CALL_FACTOR = 4L
