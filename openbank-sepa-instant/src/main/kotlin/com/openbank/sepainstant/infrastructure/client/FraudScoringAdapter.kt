// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.sepainstant.application.port.out.FraudScoreCommand
import com.openbank.sepainstant.application.port.out.FraudScoreOutcome
import com.openbank.sepainstant.application.port.out.FraudScoringPort
import com.openbank.sepainstant.application.port.out.FraudVerdict
import com.openbank.sepainstant.infrastructure.observability.FraudScoringMetrics
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Resilient, **fail-OPEN** adapter over [FraudScoreClient] (ADR-0084 §1, SHADOW phase). Unlike the
 * sanctions adapter (fail-closed), fraud scoring in shadow must have ZERO customer impact: a fault,
 * timeout or open breaker maps to an [FraudVerdict.ALLOW] outcome and is logged, never propagated.
 * Uni<>-native to match the sepa-instant reactive contract (no coroutines). No @Retry by design.
 */
@ApplicationScoped
class FraudScoringAdapter(@RestClient private val client: FraudScoreClient, private val metrics: FraudScoringMetrics) :
    FraudScoringPort {

    @Inject
    lateinit var self: FraudScoringAdapter

    private val log = Logger.getLogger(FraudScoringAdapter::class.java)

    /**
     * Fail-OPEN by decision, not by accident (#4221). The verdict is **observed, never enforced**
     * (`SctInstPaymentService.scoreFraudShadow` logs a non-ALLOW verdict and proceeds identically
     * either way), so failing closed would stop payments to protect a value nothing acts on. What
     * was wrong was not the fallback but that it was invisible: it is now flagged on the outcome
     * ([FraudScoreOutcome.synthetic]), counted, and reflected in the
     * `openbank_fraud_scoring_degraded` gauge.
     *
     * Mutiny's `onFailure()` spans `Throwable`, so an `Error` is covered by the recovery itself —
     * provided the Uni is assembled lazily; see the comment on `deferred` below.
     */
    override fun score(command: FraudScoreCommand): Uni<FraudScoreOutcome> =
        // `deferred`, not a bare call: assembling the Uni runs `client.score(...)` synchronously, so
        // a throw there (an `Error` from a classloading or fault-tolerance fault) would escape past
        // every `onFailure()` below and reach the payment path. Deferring turns it into a failure
        // the recovery can see — the Mutiny equivalent of the coroutine siblings' catch(Throwable).
        Uni.createFrom().deferred { self.scoreWithResilience(command) }
            .invoke { _ -> metrics.recordReal() }
            .onFailure().invoke { ex ->
                metrics.recordSynthetic()
                log.warnf(
                    ex,
                    "Fraud scoring unavailable (rail=%s); returning SYNTHETIC ALLOW — this payment was NOT scored",
                    command.rail,
                )
            }
            .onFailure().recoverWithItem(SYNTHETIC_ALLOW)

    @Suppress("MagicNumber")
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Timeout(3_000)
    open fun scoreWithResilience(command: FraudScoreCommand): Uni<FraudScoreOutcome> = client.score(
        FraudScoreClientRequest(
            amount = command.amount,
            currency = command.currency,
            rail = command.rail,
            accountId = command.accountId,
            counterpartyId = command.counterpartyId,
        ),
    ).map { response ->
        FraudScoreOutcome(
            verdict = mapVerdict(response.verdict),
            score = response.score,
            ruleVersion = response.ruleVersion,
            reasons = response.reasons,
        )
    }

    private fun mapVerdict(remote: String?): FraudVerdict = when (remote?.uppercase()) {
        "ALLOW" -> FraudVerdict.ALLOW
        "CHALLENGE" -> FraudVerdict.CHALLENGE
        "REVIEW" -> FraudVerdict.REVIEW
        "DECLINE" -> FraudVerdict.DECLINE
        else -> FraudVerdict.ALLOW
    }

    companion object {
        /**
         * The verdict returned when fraud-service could not be reached. `synthetic = true` is the
         * load-bearing field: `ruleVersion = "unavailable"` conveys the same thing but is a magic
         * string, and every caller compared only `verdict`.
         */
        val SYNTHETIC_ALLOW = FraudScoreOutcome(
            verdict = FraudVerdict.ALLOW,
            score = 0,
            ruleVersion = "unavailable",
            reasons = listOf("fraud-service-unavailable"),
            synthetic = true,
        )
    }
}
