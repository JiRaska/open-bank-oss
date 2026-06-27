// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.FraudVerdict
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

@ApplicationScoped
class FraudScoringAdapter(@RestClient private val client: FraudScoreClient) : FraudScoringPort {

    @Inject
    lateinit var self: FraudScoringAdapter

    private val log = Logger.getLogger(FraudScoringAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun score(command: FraudScoreCommand): FraudScoreOutcome = try {
        self.scoreWithResilience(command)
    } catch (ex: Exception) {
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

    private fun mapVerdict(remote: String?): FraudVerdict = when (remote?.uppercase()) {
        "ALLOW" -> FraudVerdict.ALLOW
        "CHALLENGE" -> FraudVerdict.CHALLENGE
        "REVIEW" -> FraudVerdict.REVIEW
        "DECLINE" -> FraudVerdict.DECLINE
        else -> FraudVerdict.ALLOW
    }
}
