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
import java.math.RoundingMode
import java.util.Currency
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

/**
 * Mirror of fraud-service's `ScoreFraudRequest` (`FraudResource.kt`), field for field.
 *
 * It did not use to be: this DTO sent `currencyCode` and no `rail`, both of which the provider
 * declares non-null, so every request was refused with a 400 before scoring — and the adapter's
 * broad catch turned that into a debug line. Shadow scoring had never once scored a card
 * authorisation (#12064). `amount` is in MAJOR units, as on every other rail.
 */
data class FraudScoreRequest(
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val accountId: UUID?,
    val counterpartyId: UUID? = null,
)

/** Mirror of fraud-service's `ScoreFraudResponse`: `verdict` (never `decision`) and an integer `score`. */
data class FraudScoreResponse(
    val verdict: String? = null,
    val score: Int? = null,
    val reasons: List<String>? = null,
    val ruleVersion: String? = null,
)

/**
 * Shadow scoring for card authorisations.
 *
 * **Shadow means the verdict changes nothing here**, exactly as on the four wired payment rails:
 * fraud scoring drives no payment outcome anywhere in this platform today, and the domestic-payment
 * enforcement gate that once existed was merged and then deleted (ADR-0084's 2026-08-09 correction,
 * #4403). Wiring it as shadow from the first authorisation means the model sees card traffic;
 * promoting it to enforcing is a separate decision with its own ADR, not a config flip.
 *
 * A scoring failure is [FraudScoringOutcome.FAILED] (counted, and warned about at most once a
 * minute) and never an exception out of this method: the
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
    private val lastWarnNanos = AtomicLong(0L)
    private val failuresSinceLastWarn = AtomicLong(0L)

    override suspend fun score(authorization: CardAuthorization): FraudScore {
        if (!scoringEnabled) return FraudScore(FraudScoringOutcome.SKIPPED_DISABLED, null, null)
        return try {
            val response = client.score(
                FraudScoreRequest(
                    amount = toMajorUnits(authorization.amountMinorUnits, authorization.currencyCode),
                    currency = authorization.currencyCode.uppercase(),
                    rail = RAIL_CARD,
                    accountId = authorization.accountId,
                ),
            )
            FraudScore(FraudScoringOutcome.SCORED, response.score?.toDouble(), response.verdict)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a shadow control must not be able to fail the path it is
            // shadowing, and the authorisation this describes is already decided and committed.
            // But NOT quiet: a debug line here is how a 400 on every call went unseen (#12064).
            // The FAILED outcome is counted by openbank_card_processing_fraud_scores_total, and
            // the first failure in each window is logged at WARN so a log search finds it too.
            warnRateLimited(authorization, e)
            FraudScore(FraudScoringOutcome.FAILED, null, null)
        }
    }

    private fun warnRateLimited(authorization: CardAuthorization, e: Exception) {
        val suppressed = failuresSinceLastWarn.incrementAndGet()
        val now = System.nanoTime()
        val last = lastWarnNanos.get()
        if ((last == 0L || now - last >= WARN_INTERVAL_NANOS) && lastWarnNanos.compareAndSet(last, now)) {
            failuresSinceLastWarn.set(0)
            log.warnf(
                e,
                "shadow fraud scoring FAILED for authorization %s (%d failure(s) since the last warning)",
                authorization.id,
                suppressed,
            )
        } else {
            log.debugf(e, "shadow fraud scoring failed for authorization %s", authorization.id)
        }
    }

    private fun toMajorUnits(minorUnits: Long, currencyCode: String): BigDecimal {
        val digits = runCatching { Currency.getInstance(currencyCode.uppercase()).defaultFractionDigits }
            .getOrDefault(DEFAULT_FRACTION_DIGITS)
            .coerceAtLeast(0)
        return BigDecimal.valueOf(minorUnits).movePointLeft(digits).setScale(digits, RoundingMode.UNNECESSARY)
    }

    private companion object {
        /** ADR-0103 D2 vocabulary, the same value the ledger posting sends. */
        const val RAIL_CARD = "CARD"
        const val DEFAULT_FRACTION_DIGITS = 2
        val WARN_INTERVAL_NANOS: Long = TimeUnit.MINUTES.toNanos(1)
    }
}
