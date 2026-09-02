// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fx.application.port.`in`.ConvertCommand
import com.openbank.fx.application.port.`in`.FxUseCase
import com.openbank.fx.application.port.`in`.GetRateHistoryQuery
import com.openbank.fx.application.port.`in`.GetRateQuery
import com.openbank.fx.application.port.`in`.ResolvedRate
import com.openbank.fx.application.port.out.AmlCasePort
import com.openbank.fx.application.port.out.AmlCaseRiskLevel
import com.openbank.fx.application.port.out.FraudScoreCommand
import com.openbank.fx.application.port.out.FraudScoringPort
import com.openbank.fx.application.port.out.FraudVerdict
import com.openbank.fx.application.port.out.FxConversionRepository
import com.openbank.fx.application.port.out.FxRateRepository
import com.openbank.fx.application.port.out.OpenAmlCaseCommand
import com.openbank.fx.application.port.out.SanctionsScreeningPort
import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.fx.domain.event.FxConversionExecuted
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionMath
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateType
import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningPolicy
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ApplicationScoped
@Suppress("LongParameterList")
class FxService(
    private val rateRepo: FxRateRepository,
    private val convRepo: FxConversionRepository,
    private val objectMapper: ObjectMapper,
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

        // #1033: FX conversions never actually reached Kafka — KafkaFxEventPublisher.publish()
        // was a no-op stub. settle() now writes to the same transactional outbox every other
        // money-path service uses (FxOutboxRepository -> fx-events-out -> openbank.fx.conversion.completed).
        const val EVENT_FX_CONVERSION_EXECUTED = "fx.conversion.executed.v1"
    }

    override suspend fun getRate(query: GetRateQuery) =
        resolveRate(query.baseCurrency, query.quoteCurrency, query.rateType)

    /**
     * The quote for base/quote, falling back to the inverse of quote/base when only that
     * direction is stored.
     *
     * The ČNB fixing — the only live source ingested here — publishes FOREIGN→CZK exclusively, so
     * every stored pair is `X/CZK`. Without this fallback `CZK/EUR` has no answer, which is not a
     * transient failure: it is every customer-initiated CZK→foreign exchange, permanently. The
     * customer edge turns the resulting null into `fx_rate_unavailable` + HTTP 502, so the app
     * reports a gateway error for what is really a missing derivation.
     *
     * Inversion swaps bid and ask — see [FxRate.inverted]. A derived quote is reported with
     * [ResolvedRate.derivedFrom] naming the stored row, so the REST layer can null the response
     * `id` (#3374); the domain rate keeps the source id either way, so `FxConversion.rateId`
     * always references a real `fx_rates` row.
     */
    private suspend fun resolveRate(base: String, quote: String, type: RateType): ResolvedRate? =
        rateRepo.findLatest(base, quote, type)?.let { ResolvedRate(it, derivedFrom = null) }
            ?: rateRepo.findLatest(quote, base, type)?.inverted()?.let { ResolvedRate(it, derivedFrom = it.id) }

    override suspend fun getAllRates() = rateRepo.findAll()

    override suspend fun getRateHistory(query: GetRateHistoryQuery): List<FxRate> {
        val base = query.baseCurrency.uppercase()
        val quote = query.quoteCurrency.uppercase()
        val direct = rateRepo.findHistory(base, quote, query.source, query.from, query.to, query.limit, query.offset)
        if (direct.isNotEmpty()) return direct

        // History must resolve the same pair directions as today's quote. ČNB stores only X/CZK,
        // while a customer commonly asks for CZK/X. Returning an empty chart for a pair that the
        // quote endpoint can price is both surprising and financially misleading.
        return rateRepo.findHistory(quote, base, query.source, query.from, query.to, query.limit, query.offset)
            .map(FxRate::inverted)
    }

    override suspend fun convert(cmd: ConvertCommand): FxConversion {
        convRepo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        // Same resolution as the read path: a conversion must not be refused for a pair the bank
        // can price perfectly well from the other side.
        val rate = resolveRate(cmd.fromCurrency, cmd.toCurrency, RateType.SPOT)?.rate
            ?: error("No FX rate available for ${cmd.fromCurrency}/${cmd.toCurrency}")
        require(rate.isValid(Instant.now(clock))) { "FX rate expired for ${cmd.fromCurrency}/${cmd.toCurrency}" }

        val toAmount = FxConversionMath.convertedAmountMinorUnits(cmd.fromAmountMinorUnits, rate.askRate)
        val fee = FxConversionMath.feeMinorUnits(cmd.fromAmountMinorUnits)

        // ADR-0032: screen the converting party synchronously *before* the conversion is allowed to
        // settle. CLEAR settles, BLOCK fails with a CRITICAL AML case, REVIEW / screening-unavailable
        // hold the conversion in PENDING (fail-closed — never settled un-screened).
        metrics.paymentSubmitted("fx", "${cmd.fromCurrency}_${cmd.toCurrency}")
        val result = applyScreening(cmd, rate, toAmount, fee)
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
        val conv = conversion(
            cmd,
            rate,
            toAmountMinorUnits,
            feeMinorUnits,
            conversionId,
            FxConversionStatus.SETTLED,
            now,
            settledAt = now,
        )
        val event = FxConversionExecuted(
            conv.id,
            conv.partyId,
            conv.fromCurrency,
            conv.toCurrency,
            conv.fromAmountMinorUnits,
            conv.toAmountMinorUnits,
            conv.appliedRate,
            occurredAt = now,
        )
        val saved = convRepo.saveWithOutbox(
            conv,
            OutboxMessage(
                aggregateId = conv.id,
                eventType = EVENT_FX_CONVERSION_EXECUTED,
                payload = objectMapper.writeValueAsString(event),
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
        if (outcome.synthetic) {
            // #4221: a conversion that was never scored is not a conversion that scored clean.
            log.warnf(
                "Fraud scoring UNAVAILABLE for conversion by party %s — synthetic ALLOW, no fraud " +
                    "verdict was obtained (see openbank_fraud_scoring_degraded{service=\"fx\"})",
                cmd.partyId,
            )
        } else if (outcome.verdict != FraudVerdict.ALLOW) {
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
