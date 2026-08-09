// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.workflow

import com.openbank.fx.application.port.out.AmlCasePort
import com.openbank.fx.application.port.out.AmlCaseRiskLevel
import com.openbank.fx.application.port.out.FraudScoreCommand
import com.openbank.fx.application.port.out.FraudScoringPort
import com.openbank.fx.application.port.out.FraudVerdict
import com.openbank.fx.application.port.out.FxConversionRepository
import com.openbank.fx.application.port.out.OpenAmlCaseCommand
import com.openbank.fx.application.port.out.SanctionsScreeningPort
import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningPolicy
import com.openbank.fx.domain.screening.ScreeningRole
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
private const val ALERT_AML_HOLD = "AML_HOLD"
private const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"

@ApplicationScoped
open class FxActivitiesImpl(
    private val conversionRepository: FxConversionRepository,
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
    private val fraudScoringPort: FraudScoringPort,
    private val clock: Clock,
) : FxActivities {

    private val log = Logger.getLogger(FxActivitiesImpl::class.java)

    protected open fun <T> vtx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    override fun screenConversion(conversionId: UUID): ScreeningDecision = vtx {
        val conv = conversionRepository.findById(conversionId)
            ?: error("Conversion $conversionId not found during screening activity")

        val result = try {
            screeningPort.screen(conv.partyId.toString(), ScreeningRole.DEBTOR, "$conversionId:party")
        } catch (ex: ScreeningUnavailableException) {
            log.warnf(ex, "Sanctions screening unavailable for conversion %s; returning REVIEW", conversionId)
            openCaseQuietly(
                conv = conv,
                riskLevel = AmlCaseRiskLevel.MEDIUM,
                alertCode = ALERT_SCREENING_UNAVAILABLE,
                detail = ex.message,
                matchedEntity = null,
            )
            return@vtx ScreeningDecision.REVIEW
        }

        val results = listOf(result)
        val decision = ScreeningPolicy.decide(results)

        if (decision == ScreeningDecision.BLOCK || decision == ScreeningDecision.REVIEW) {
            val nonClear = results.filter {
                it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
            }
            val detail = nonClear
                .joinToString("; ") { "${it.role} '${it.subject}' ${it.status} score=${it.score}" }
                .ifBlank { "no actionable matches" }
            val riskLevel = if (decision == ScreeningDecision.BLOCK) {
                AmlCaseRiskLevel.CRITICAL
            } else {
                AmlCaseRiskLevel.HIGH
            }
            val alertCode = if (decision == ScreeningDecision.BLOCK) ALERT_SANCTIONS_HIT else ALERT_AML_HOLD
            openCaseQuietly(
                conv = conv,
                riskLevel = riskLevel,
                alertCode = alertCode,
                detail = detail,
                matchedEntity = nonClear.firstNotNullOfOrNull { it.matchedEntity },
            )
        }

        decision
    }

    override fun settleConversion(conversionId: UUID): Unit = vtx {
        val conv = conversionRepository.findById(conversionId)
            ?: error("Conversion $conversionId not found during settle activity")
        if (conv.status == FxConversionStatus.SETTLED) return@vtx
        val now = Instant.now(clock)
        conversionRepository.save(
            conv.copy(status = FxConversionStatus.SETTLED, settledAt = now),
        )
    }

    override fun blockConversion(conversionId: UUID): Unit = vtx {
        val conv = conversionRepository.findById(conversionId)
            ?: error("Conversion $conversionId not found during block activity")
        if (conv.status == FxConversionStatus.FAILED) return@vtx
        conversionRepository.save(conv.copy(status = FxConversionStatus.FAILED))
    }

    override fun holdConversion(conversionId: UUID): Unit = vtx {
        val conv = conversionRepository.findById(conversionId)
            ?: error("Conversion $conversionId not found during hold activity")
        if (conv.status == FxConversionStatus.PENDING) return@vtx
        conversionRepository.save(conv.copy(status = FxConversionStatus.PENDING))
    }

    override fun shadowFraudScore(conversionId: UUID): Unit = vtx {
        val conv = conversionRepository.findById(conversionId)
            ?: error("Conversion $conversionId not found during fraud score activity")
        val outcome = fraudScoringPort.score(
            FraudScoreCommand(
                amount = BigDecimal(conv.fromAmountMinorUnits).movePointLeft(2),
                currency = conv.fromCurrency,
                rail = "FX",
                accountId = conv.accountId,
                counterpartyId = null,
            ),
        )
        if (outcome.synthetic) {
            // #4221: a conversion that was never scored is not a conversion that scored clean.
            log.warnf(
                "Fraud scoring UNAVAILABLE for conversion %s — synthetic ALLOW, this conversion carries " +
                    "no fraud verdict (see openbank_fraud_scoring_degraded{service=\"fx\"})",
                conversionId,
            )
        } else if (outcome.verdict != FraudVerdict.ALLOW) {
            log.infof(
                "Fraud SHADOW verdict %s (score=%d, rules=%s) for conversion %s — observed, not enforced",
                outcome.verdict,
                outcome.score,
                outcome.ruleVersion,
                conversionId,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
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
                    "${conv.partyId} ${conv.fromCurrency}->${conv.toCurrency} ${conv.fromAmountMinorUnits}",
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
}
