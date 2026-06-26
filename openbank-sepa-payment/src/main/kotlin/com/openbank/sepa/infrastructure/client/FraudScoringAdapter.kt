// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.FraudScoreCommand
import com.openbank.sepa.application.port.out.FraudScoreOutcome
import com.openbank.sepa.application.port.out.FraudScoringPort
import com.openbank.sepa.application.port.out.FraudVerdict
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Resilient, **fail-OPEN** adapter over [FraudScoreClient] (ADR-0084 §1, SHADOW phase). Unlike the
 * sanctions adapter (fail-closed — holds the payment), fraud scoring in shadow must have ZERO customer
 * impact: a fault, timeout or open breaker maps to an [FraudVerdict.ALLOW] outcome and is logged, never
 * propagated to the use-case. The circuit breaker keeps a flapping fraud-service from adding latency to
 * the payment path. There is no @Retry by design — a shadow score is best-effort, not worth retrying.
 */
@ApplicationScoped
class FraudScoringAdapter(@RestClient private val client: FraudScoreClient) : FraudScoringPort {

    @Inject
    lateinit var self: FraudScoringAdapter

    private val log = Logger.getLogger(FraudScoringAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun score(command: FraudScoreCommand): FraudScoreOutcome = try {
        self.scoreWithResilience(command)
    } catch (ex: Exception) {
        // Fail-OPEN: shadow scoring must never block or hold a payment.
        log.warnf(ex, "Fraud scoring unavailable (rail=%s); shadow ALLOW", command.rail)
        FraudScoreOutcome(FraudVerdict.ALLOW, 0, "unavailable", listOf("fraud-service-unavailable"))
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

    /** Unknown verdicts map to ALLOW — in shadow the verdict is logged, not acted upon. */
    private fun mapVerdict(remote: String?): FraudVerdict = when (remote?.uppercase()) {
        "ALLOW" -> FraudVerdict.ALLOW
        "CHALLENGE" -> FraudVerdict.CHALLENGE
        "REVIEW" -> FraudVerdict.REVIEW
        "DECLINE" -> FraudVerdict.DECLINE
        else -> FraudVerdict.ALLOW
    }
}
