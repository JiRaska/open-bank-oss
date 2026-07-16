// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.`in`.*
import com.openbank.interest.application.port.out.*
import com.openbank.interest.domain.model.*
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.domain.tax.WithholdingTaxPolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
@Suppress("LongParameterList")
class InterestService(
    private val configRepo: InterestRateConfigRepository,
    private val accrualRepo: InterestAccrualRepository,
    private val capitalizationRepo: InterestCapitalizationRepository,
    private val taxProfilePort: TaxProfilePort,
    @ConfigProperty(name = "openbank.interest.day-count-convention", defaultValue = "ACT_365")
    private val defaultDayCount: String,
    private val clock: Clock,
) : AccrueInterestUseCase,
    CapitalizeInterestUseCase,
    GetAccrualsUseCase,
    ManageRateConfigUseCase {

    @Inject
    constructor(
        configRepo: InterestRateConfigRepository,
        accrualRepo: InterestAccrualRepository,
        capitalizationRepo: InterestCapitalizationRepository,
        taxProfilePort: TaxProfilePort,
        @ConfigProperty(name = "openbank.interest.day-count-convention", defaultValue = "ACT_365")
        defaultDayCount: String,
    ) : this(
        configRepo,
        accrualRepo,
        capitalizationRepo,
        taxProfilePort,
        defaultDayCount,
        Clock.systemUTC(),
    )

    override fun accrue(request: AccrualRequest): Uni<InterestAccrual> =
        configRepo.findActiveForProduct(request.productId, request.accrualDate).flatMap { config ->
            if (config == null) {
                Uni.createFrom().failure(
                    IllegalStateException("No active rate config for product ${request.productId}"),
                )
            } else {
                val divisor = when (config.dayCount) {
                    DayCount.ACT_360 -> BigDecimal(360)
                    else -> BigDecimal(365)
                }
                val dailyRate = config.annualRate.divide(divisor, 10, RoundingMode.HALF_UP)
                val accruedAmount = request.balance.multiply(dailyRate).setScale(6, RoundingMode.HALF_UP)
                val accrual = InterestAccrual(
                    accountId = request.accountId,
                    productId = request.productId,
                    configId = config.id,
                    accrualDate = request.accrualDate,
                    balance = request.balance,
                    dailyRate = dailyRate,
                    accruedAmount = accruedAmount,
                    currency = request.currency,
                    createdAt = OffsetDateTime.now(clock),
                )
                accrualRepo.save(accrual)
            }
        }

    override fun accrueAll(date: LocalDate): Uni<Int> {
        // In production: fetch all active accounts with interest products and accrue
        return Uni.createFrom().item(0)
    }

    override fun capitalize(accountId: UUID, productId: String, toDate: LocalDate): Uni<InterestCapitalization> =
        accrualRepo.findPendingCapitalization(accountId, productId, toDate).flatMap { accruals ->
            // Normalized so a "czk"/"CZK" mix is one currency, not two.
            val currencies = accruals.map { it.currency.uppercase() }.distinct()
            when {
                accruals.isEmpty() ->
                    Uni.createFrom().failure(IllegalStateException("No pending accruals to capitalize"))
                currencies.size > 1 -> Uni.createFrom().failure(mixedCurrencyFailure(accountId, productId, currencies))
                else -> capitalizePending(accountId, productId, toDate, accruals, currencies.single())
            }
        }

    /**
     * A pending set spanning several currencies has no single correct capitalization: the accrued
     * numerics are not commensurable (summing 100 CZK and 5 EUR into "105" is nonsense), and
     * [WithholdingTaxPolicy] assesses per currency (§E — only CZK is withheld in v1), so folding
     * them would also withhold against the wrong base. There is no safe guess, so refuse loudly and
     * leave every accrual `ACCRUING`.
     *
     * NOTE (issue #1265): this refusal is permanent as shipped. Nothing binds a currency to a
     * product ([InterestRateConfig] has no currency field, and [AccrualStatus.REVERSED] /
     * [AccrualStatus.SUSPENDED] have zero writers anywhere), so there is currently no operator
     * or API path that can split, reverse, or otherwise unwedge the pending set once this fires.
     * Do not tell a caller to "split the accruals" or "investigate" as if that were actionable —
     * see #1265 for the options (currency on the product config, currency in the accrual unique
     * key, or a guarded reversal endpoint) and get a decision before promising a way out here.
     */
    private fun mixedCurrencyFailure(accountId: UUID, productId: String, currencies: List<String>) =
        IllegalStateException(
            "Refusing to capitalize a mixed-currency accrual set for account=$accountId product=$productId: " +
                "pending accruals are denominated in ${currencies.sorted()}. Interest must be capitalized per " +
                "(product, currency), but there is currently no operator or API path to split, reverse, or " +
                "otherwise resolve this set — see issue #1265. Manual intervention is required.",
        )

    /** Capitalizes one single-currency pending set (ADR-0033 §B/§D). See [capitalize] for the guards. */
    private fun capitalizePending(
        accountId: UUID,
        productId: String,
        toDate: LocalDate,
        accruals: List<InterestAccrual>,
        currency: String,
    ): Uni<InterestCapitalization> {
        val total = accruals.fold(BigDecimal.ZERO) { acc, a -> acc + a.accruedAmount }
        val gross = total.setScale(4, RoundingMode.HALF_UP)
        val periodFrom = accruals.minOf { it.accrualDate }
        val now = OffsetDateTime.now(clock)
        // ADR-0033: withhold at the credit (capitalization), crediting net; record the liability.
        return taxProfilePort.resolve(accountId).flatMap { profile ->
            val tax = WithholdingTaxPolicy.compute(gross, currency, profile, toDate)
            val net = tax.netAmount.setScale(4, RoundingMode.HALF_UP)
            val cap = InterestCapitalization(
                accountId = accountId,
                productId = productId,
                periodFrom = periodFrom,
                periodTo = toDate,
                totalAccrued = total,
                capitalizedAmount = net,
                grossAmount = gross,
                taxAmount = tax.taxAmount,
                netAmount = net,
                currency = currency,
                createdAt = now,
            )
            val withholding = WithholdingTax(
                capitalizationId = cap.id,
                accountId = accountId,
                periodFrom = periodFrom,
                periodTo = toDate,
                taxableBase = tax.taxableBase,
                rate = tax.rate,
                taxAmount = tax.taxAmount,
                currency = currency,
                treatment = tax.treatment,
                exemptCode = tax.exemptCode,
                createdAt = now,
            )
            // ONE transaction for the whole credit: capitalization + withholding + outbox event +
            // the status-guarded ACCRUING -> CAPITALIZED flip. Previously these were four separate
            // transactions, so a crash between them could commit the capitalization while leaving
            // the accruals ACCRUING — a retry then re-credited the customer AND re-booked the tax.
            capitalizationRepo.saveWithOutbox(
                cap,
                withholding,
                withholdingRecordedEvent(cap, withholding),
                accruals.map { it.id },
                now,
            )
        }
    }

    /** Builds the versioned `interest.withholding.recorded` outbox event (ADR-0033 §F). */
    private fun withholdingRecordedEvent(cap: InterestCapitalization, withholding: WithholdingTax): OutboxMessage {
        val payload = "{\"schemaVersion\":1," +
            "\"capitalizationId\":\"${cap.id}\",\"withholdingId\":\"${withholding.id}\"," +
            "\"accountId\":\"${cap.accountId}\",\"productId\":\"${cap.productId}\"," +
            "\"periodFrom\":\"${cap.periodFrom}\",\"periodTo\":\"${cap.periodTo}\"," +
            "\"currency\":\"${cap.currency}\",\"grossAmount\":\"${cap.grossAmount}\"," +
            "\"taxableBase\":\"${withholding.taxableBase}\",\"rate\":\"${withholding.rate}\"," +
            "\"taxAmount\":\"${withholding.taxAmount}\",\"netAmount\":\"${cap.netAmount}\"," +
            "\"treatment\":\"${withholding.treatment}\",\"status\":\"${withholding.status}\"}"
        return OutboxMessage(
            eventId = UUID.randomUUID(),
            aggregateId = cap.id,
            eventType = "interest.withholding.recorded.v1",
            payload = payload,
        )
    }

    override fun capitalizeAll(toDate: LocalDate): Uni<Int> = Uni.createFrom().item(0)

    override fun listAllAccruals(): Uni<List<InterestAccrual>> = accrualRepo.findAll()

    override fun getAccruals(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>> =
        accrualRepo.findByAccountId(accountId, from, to)

    override fun getSummary(accountId: UUID, from: LocalDate, to: LocalDate): Uni<AccrualSummary> =
        accrualRepo.findByAccountId(accountId, from, to).map { accruals ->
            AccrualSummary(
                accountId = accountId,
                totalAccrued = accruals.fold(BigDecimal.ZERO) { acc, a -> acc + a.accruedAmount },
                currency = accruals.firstOrNull()?.currency ?: "EUR",
                fromDate = from,
                toDate = to,
                accrualCount = accruals.size,
            )
        }

    override fun getCapitalizations(accountId: UUID): Uni<List<InterestCapitalization>> =
        capitalizationRepo.findByAccountId(accountId)

    override fun createConfig(config: InterestRateConfig): Uni<InterestRateConfig> = configRepo.save(config)
    override fun getConfig(id: UUID): Uni<InterestRateConfig?> = configRepo.findById(id)
    override fun listConfigs(productId: String?): Uni<List<InterestRateConfig>> =
        if (productId != null) configRepo.findByProductId(productId) else configRepo.findAll()
    override fun deactivateConfig(id: UUID): Uni<InterestRateConfig> = configRepo.findById(id).flatMap { config ->
        if (config == null) {
            Uni.createFrom().failure(IllegalArgumentException("Config not found"))
        } else {
            configRepo.update(config.copy(active = false, updatedAt = OffsetDateTime.now(clock)))
        }
    }
}
