// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.FraudVerdict
import com.openbank.domestic.infrastructure.observability.FraudScoringMetrics
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

@ApplicationScoped
class FraudScoringAdapter(@RestClient private val client: FraudScoreClient, private val metrics: FraudScoringMetrics) :
    FraudScoringPort {

    @Inject
    lateinit var self: FraudScoringAdapter

    private val log = Logger.getLogger(FraudScoringAdapter::class.java)

    /**
     * Fail-OPEN by decision, not by accident (#4221). The verdict this adapter returns is
     * **observed, never enforced** — the only caller logs a non-ALLOW verdict and then proceeds
     * identically either way — so failing closed here would stop payments to protect a value
     * nothing acts on. What was wrong was not the fallback but that the fallback was invisible:
     * it is now flagged on the outcome ([FraudScoreOutcome.synthetic]), counted, and reflected in
     * the `openbank_fraud_scoring_degraded` gauge.
     *
     * `Throwable`, not `Exception`: a fault crossing into a rest-client or fault-tolerance
     * interceptor can surface as an `Error`, and an `Error` escaping here would propagate out of a
     * path whose entire contract is that it cannot affect the payment. `CancellationException` is
     * rethrown — cancelling the caller's coroutine is not a fraud-service outage and must not be
     * reported as one.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun score(command: FraudScoreCommand): FraudScoreOutcome = try {
        val outcome = self.scoreWithResilience(command)
        metrics.recordReal()
        outcome
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Throwable) {
        metrics.recordSynthetic()
        log.warnf(
            ex,
            "Fraud scoring unavailable (rail=%s); returning SYNTHETIC ALLOW — this payment was NOT scored",
            command.rail,
        )
        SYNTHETIC_ALLOW
    }

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Timeout(value = 3_000)
    open suspend fun scoreWithResilience(command: FraudScoreCommand): FraudScoreOutcome {
        val response = client.score(
            FraudScoreClientRequest(
                amount = command.amount,
                currency = command.currency,
                rail = command.rail,
                accountId = command.accountId,
                counterpartyId = command.counterpartyId,
            ),
        ).awaitSuspending()
        return FraudScoreOutcome(
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
