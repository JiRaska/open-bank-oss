// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.cardprocessing.application.port.out.FraudScore
import com.openbank.cardprocessing.application.port.out.FraudScoringOutcome
import com.openbank.cardprocessing.application.port.out.FraudScoringPort
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

@Path("/api/v1/fraud")
@RegisterRestClient(configKey = "fraud-api")
@OidcClientFilter
@RegisterProvider(SyntheticTaintClientFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface FraudServiceClient {
    @POST
    @Path("/score")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun score(request: FraudScoreRequest): FraudScoreResponse
}

data class FraudScoreRequest(
    val transactionId: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val amount: BigDecimal,
    val currencyCode: String,
    val channel: String,
    val countryCode: String?,
    val merchantCategory: String?,
)

data class FraudScoreResponse(val score: Double? = null, val decision: String? = null)

/**
 * Shadow scoring for card authorisations.
 *
 * **Shadow means the verdict changes nothing here**, exactly as on the four wired payment rails:
 * fraud scoring drives no payment outcome anywhere in this platform today, and the domestic-payment
 * enforcement gate that once existed was merged and then deleted (ADR-0084's 2026-08-09 correction,
 * #4403). Wiring it as shadow from the first authorisation means the model sees card traffic;
 * promoting it to enforcing is a separate decision with its own ADR, not a config flip.
 *
 * A scoring failure is [FraudScoringOutcome.FAILED] and never an exception out of this method: the
 * authorisation it describes has already been decided and committed, and a shadow control must not
 * be able to take down the path it is shadowing.
 */
@ApplicationScoped
class FraudScoringAdapter(
    @RestClient private val client: FraudServiceClient,
    @ConfigProperty(name = "openbank.card-processing.fraud-scoring-enabled", defaultValue = "true")
    private val scoringEnabled: Boolean,
) : FraudScoringPort {

    private val log = Logger.getLogger(FraudScoringAdapter::class.java)

    override suspend fun score(authorization: CardAuthorization): FraudScore {
        if (!scoringEnabled) return FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        return try {
            val response = client.score(
                FraudScoreRequest(
                    transactionId = authorization.id,
                    accountId = authorization.accountId,
                    partyId = authorization.partyId,
                    amount = BigDecimal.valueOf(authorization.amountMinorUnits),
                    currencyCode = authorization.currencyCode,
                    channel = authorization.channel.name,
                    countryCode = authorization.merchantCountry,
                    merchantCategory = authorization.category,
                ),
            )
            FraudScore(FraudScoringOutcome.SCORED, response.score, response.decision)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a shadow control must not be able to fail the path it is
            // shadowing, and the authorisation this describes is already decided and committed.
            log.debugf(e, "shadow fraud scoring failed for authorization %s", authorization.id)
            FraudScore(FraudScoringOutcome.FAILED, null, null)
        }
    }
}
