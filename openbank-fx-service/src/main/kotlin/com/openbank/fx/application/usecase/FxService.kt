// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.application.usecase

import com.openbank.fx.application.port.`in`.*
import com.openbank.fx.application.port.out.*
import com.openbank.fx.domain.model.*
import com.openbank.fx.domain.event.*
import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningPolicy
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ApplicationScoped
@Suppress("LongParameterList")
class FxService(
    private val rateRepo: FxRateRepository,
    private val convRepo: FxConversionRepository,
    private val publisher: FxEventPublisher,
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val metrics: DomainMetrics,
    private val fraudScoringPort: FraudScoringPort,
    private val clock: Clock,
) : FxUseCase {

    private val log = Logger.getLogger(FxService::class.java)

    private companion object {
        const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
        const val ALERT_AML_HOLD = "AML_HOLD"
        const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"
    }

    override suspend fun getRate(query: GetRateQuery) =
        rateRepo.findLatest(query.baseCurrency, query.quoteCurrency, query.rateType)

    override suspend fun getAllRates() = rateRepo.findAll()

    override suspend fun getRateHistory(query: GetRateHistoryQuery): List<FxRate> = rateRepo.findHistory(
        base = query.baseCurrency.uppercase(),
        quote = query.quoteCurrency.uppercase(),
        source = query.source,
        from = query.from,
        to = query.to,
        limit = query.limit,
        offset = query.offset,
    )

    override suspend fun convert(cmd: ConvertCommand): FxConversion {
        convRepo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        val rate = rateRepo.findLatest(cmd.fromCurrency, cmd.toCurrency, RateType.SPOT)
            ?: error("No FX rate available for ${cmd.fromCurrency}/${cmd.toCurrency}")
        require(rate.isValid(Instant.now(clock))) { "FX rate expired for ${cmd.fromCurrency}/${cmd.toCurrency}" }

        val fromAmount = BigDecimal(cmd.fromAmountMinorUnits)
        val toAmount = fromAmount.multiply(rate.askRate).setScale(0, RoundingMode.HALF_UP)
        val fee = fromAmount.multiply(BigDecimal("0.005")).setScale(0, RoundingMode.HALF_UP) // 0.5% fee

        // ADR-0032: screen the converting party synchronously *before* the conversion is allowed to
        // settle. CLEAR settles, BLOCK fails with a CRITICAL AML case, REVIEW / screening-unavailable
        // hold the conversion in PENDING (fail-closed — never settled un-screened).
        metrics.paymentSubmitted("fx", "${cmd.fromCurrency}_${cmd.toCurrency}")
        val result = applyScreening(cmd, rate, toAmount.toLong(), fee.toLong())
        scoreFraudShadow(cmd)
        return result
    }

    private suspend fun applyScreening(
        cmd: ConvertCommand,
        rate: FxRate,
        toAmountMinorUnits: Long,
        feeMinorUnits: Long,
    ): FxConversion {
        val conversionId = UUID.randomUUID()

        val result = try {
            screeningPort.screen(cmd.partyName, ScreeningRole.DEBTOR, "$conversionId:party")
        } catch (ex: ScreeningUnavailableException) {
            log.warnf(ex, "Sanctions screening unavailable for conversion %s; holding in PENDING", conversionId)
            return hold(
                cmd,
                rate,
                toAmountMinorUnits,
                feeMinorUnits,
                conversionId,
                AmlCaseRiskLevel.MEDIUM,
                ALERT_SCREENING_UNAVAILABLE,
                ex.message,
                null,
            )
        }
        metrics.sanctionsScreening("party")
        val results = listOf(result)

        return when (ScreeningPolicy.decide(results)) {
            ScreeningDecision.CLEAR ->
                settle(cmd, rate, toAmountMinorUnits, feeMinorUnits, conversionId)

            ScreeningDecision.REVIEW ->
                hold(
                    cmd,
                    rate,
                    toAmountMinorUnits,
                    feeMinorUnits,
                    conversionId,
                    AmlCaseRiskLevel.HIGH,
                    ALERT_AML_HOLD,
                    detail(results),
                    matchedEntity(results),
                )

            ScreeningDecision.BLOCK ->
                block(
                    cmd,
                    rate,
                    toAmountMinorUnits,
                    feeMinorUnits,
                    conversionId,
                    detail(results),
                    matchedEntity(results),
                )
        }
    }

    private suspend fun settle(
        cmd: ConvertCommand,
        rate: FxRate,
        toAmountMinorUnits: Long,
        feeMinorUnits: Long,
        conversionId: UUID,
    ): FxConversion {
        val now = Instant.now(clock)
        val saved = convRepo.save(
            conversion(
                cmd,
                rate,
                toAmountMinorUnits,
                feeMinorUnits,
                conversionId,
                FxConversionStatus.SETTLED,
                now,
                settledAt = now,
            ),
        )
        publisher.publish(
            FxConversionExecuted(
                saved.id,
                saved.partyId,
                saved.fromCurrency,
                saved.toCurrency,
                saved.fromAmountMinorUnits,
                saved.toAmountMinorUnits,
                saved.appliedRate,
                occurredAt = now,
            ),
        )
        metrics.paymentCompleted("fx", "${cmd.fromCurrency}_${cmd.toCurrency}", "completed")
        metrics.paymentProcessingDuration("fx", "completed", Duration.between(saved.createdAt, Instant.now(clock)))
        return saved
    }

    /** Holds the conversion in PENDING for a human decision via the AML case lifecycle (no settlement). */
    private suspend fun hold(
        cmd: ConvertCommand,
        rate: FxRate,
        toAmountMinorUnits: Long,
        feeMinorUnits: Long,
        conversionId: UUID,
        riskLevel: AmlCaseRiskLevel,
        alertCode: String,
        detail: String?,
        matchedEntity: String?,
    ): FxConversion {
        val saved = convRepo.save(
            conversion(
                cmd,
                rate,
                toAmountMinorUnits,
                feeMinorUnits,
                conversionId,
                FxConversionStatus.PENDING,
                Instant.now(clock),
                settledAt = null,
            ),
        )
        openCaseQuietly(saved, riskLevel, alertCode, detail, matchedEntity)
        return saved
    }

    /** Sanctions hit — the conversion is FAILED and a CRITICAL AML case is opened. */
    private suspend fun block(
        cmd: ConvertCommand,
        rate: FxRate,
        toAmountMinorUnits: Long,
        feeMinorUnits: Long,
        conversionId: UUID,
        detail: String?,
        matchedEntity: String?,
    ): FxConversion {
        val saved = convRepo.save(
            conversion(
                cmd,
                rate,
                toAmountMinorUnits,
                feeMinorUnits,
                conversionId,
                FxConversionStatus.FAILED,
                Instant.now(clock),
                settledAt = null,
            ),
        )
        openCaseQuietly(saved, AmlCaseRiskLevel.CRITICAL, ALERT_SANCTIONS_HIT, detail, matchedEntity)
        metrics.paymentCompleted("fx", "${cmd.fromCurrency}_${cmd.toCurrency}", "rejected")
        metrics.sanctionsHit("party", "block")
        return saved
    }

    private fun conversion(
        cmd: ConvertCommand,
        rate: FxRate,
        toAmountMinorUnits: Long,
        feeMinorUnits: Long,
        conversionId: UUID,
        status: FxConversionStatus,
        createdAt: Instant,
        settledAt: Instant?,
    ) = FxConversion(
        id = conversionId,
        idempotencyKey = cmd.idempotencyKey,
        partyId = cmd.partyId,
        accountId = cmd.accountId,
        fromCurrency = cmd.fromCurrency,
        toCurrency = cmd.toCurrency,
        fromAmountMinorUnits = cmd.fromAmountMinorUnits,
        toAmountMinorUnits = toAmountMinorUnits,
        appliedRate = rate.askRate,
        feeMinorUnits = feeMinorUnits,
        rateId = rate.id,
        status = status,
        createdAt = createdAt,
        settledAt = settledAt,
    )

    /** Opening the AML case is best-effort: a case-store outage must not flip the screening verdict. */
    private suspend fun openCaseQuietly(
        conv: FxConversion,
        riskLevel: AmlCaseRiskLevel,
        alertCode: String,
        detail: String?,
        matchedEntity: String?,
    ) {
        try {
            amlCasePort.openCase(
                OpenAmlCaseCommand(
                    idempotencyKey = "aml-${conv.id}-$alertCode",
                    conversionId = conv.id,
                    partyId = conv.partyId,
                    accountId = conv.accountId,
                    customerReference =
                    "${conv.partyId} ${conv.fromCurrency}->${conv.toCurrency} " +
                        "${conv.fromAmountMinorUnits}",
                    riskLevel = riskLevel,
                    alertCode = alertCode,
                    alertDetail = detail,
                    matchedEntity = matchedEntity,
                ),
            )
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to open AML case (%s) for conversion %s", alertCode, conv.id)
        }
    }

    // ADR-0084 §4.1: shadow phase — observe fraud signals without enforcing; always fail-open.
    private suspend fun scoreFraudShadow(cmd: ConvertCommand) {
        val outcome = fraudScoringPort.score(
            FraudScoreCommand(
                amount = BigDecimal(cmd.fromAmountMinorUnits).movePointLeft(2),
                currency = cmd.fromCurrency,
                rail = "FX",
                accountId = cmd.accountId,
                counterpartyId = null,
            ),
        )
        if (outcome.verdict != FraudVerdict.ALLOW) {
            log.infof(
                "Fraud SHADOW verdict %s (score=%d, rules=%s) for conversion by party %s — observed, not enforced",
                outcome.verdict,
                outcome.score,
                outcome.ruleVersion,
                cmd.partyId,
            )
        }
    }

    private fun detail(results: List<ScreeningResult>): String =
        results.filter { it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED }
            .joinToString("; ") { "${it.role} '${it.subject}' ${it.status} score=${it.score}" }
            .ifBlank { "no actionable matches" }

    private fun matchedEntity(results: List<ScreeningResult>): String? =
        results.firstNotNullOfOrNull { it.matchedEntity }

    override suspend fun getConversion(id: UUID) = convRepo.findById(id)
    override suspend fun listConversions(partyId: UUID) = convRepo.findByPartyId(partyId)
}
