// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.sepainstant.application.port.out.FraudScoreCommand
import com.openbank.sepainstant.application.port.out.FraudScoreOutcome
import com.openbank.sepainstant.application.port.out.FraudScoringPort
import com.openbank.sepainstant.application.port.out.FraudVerdict
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
class FraudScoringAdapter(@RestClient private val client: FraudScoreClient) : FraudScoringPort {

    @Inject
    lateinit var self: FraudScoringAdapter

    private val log = Logger.getLogger(FraudScoringAdapter::class.java)

    private val allowOnFault =
        FraudScoreOutcome(FraudVerdict.ALLOW, 0, "unavailable", listOf("fraud-service-unavailable"))

    override fun score(command: FraudScoreCommand): Uni<FraudScoreOutcome> = self.scoreWithResilience(command)
        .onFailure().invoke { ex ->
            log.warnf(ex, "Fraud scoring unavailable (rail=%s); shadow ALLOW", command.rail)
        }
        .onFailure().recoverWithItem(allowOnFault)

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
}
