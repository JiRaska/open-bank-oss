// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.kyc.application.port.out.PepScreeningPort
import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.PepScreeningUnavailableException
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resilient adapter over [SanctionsServiceClient], scoped to the `PEP_GLOBAL` list type
 * (openbank-sanctions-service's already-imported OpenSanctions PEP dataset — ADR-0116 delivery
 * note "External watchlist: Planned"; this is that first increment, PEP-only).
 *
 * Unlike the payment-path sanctions gate (fails closed, holds the payment), a transient outage
 * here is mapped to [PepScreeningStatus.UNAVAILABLE] rather than thrown past the port — the
 * caller ([com.openbank.kyc.application.PepScreeningService]) routes that to
 * [com.openbank.kyc.domain.model.CheckStatus.MANUAL_REVIEW] so a case never silently records a
 * false PASSED PEP check just because the downstream service was briefly unreachable.
 */
@ApplicationScoped
class SanctionsScreeningAdapter(@RestClient private val client: SanctionsServiceClient) : PepScreeningPort {

    @Inject
    lateinit var self: SanctionsScreeningAdapter

    // Deliberately broad: any failure surfaced by the resilience-wrapped call below (transport
    // fault, exhausted retries, open circuit breaker, timeout) must degrade to UNAVAILABLE, not
    // propagate a specific exception type the caller would need to enumerate — mirrors
    // openbank-domestic-payment's SanctionsScreeningAdapter (ADR-0032 §D).
    @Suppress("TooGenericExceptionCaught")
    override suspend fun screenForPep(name: String, idempotencyKey: String): PepScreeningResult = try {
        self.screenWithResilience(name, idempotencyKey)
    } catch (ex: Exception) {
        throw PepScreeningUnavailableException(ex)
    }

    @CircuitBreaker(
        requestVolumeThreshold = CB_REQUEST_VOLUME_THRESHOLD,
        failureRatio = CB_FAILURE_RATIO,
        delay = CB_DELAY_MS,
        successThreshold = CB_SUCCESS_THRESHOLD,
    )
    @Retry(
        maxRetries = RETRY_MAX_RETRIES,
        delay = RETRY_DELAY_MS,
        jitter = RETRY_JITTER_MS,
        retryOn = [Exception::class],
    )
    @Timeout(TIMEOUT_MS)
    open suspend fun screenWithResilience(name: String, idempotencyKey: String): PepScreeningResult {
        val response = client.screen(
            ScreenRequest(
                idempotencyKey = idempotencyKey,
                entityType = ENTITY_TYPE,
                name = name,
                listTypes = listOf("PEP_GLOBAL"),
            ),
        ).awaitSuspending()
        val score = response.overallScore ?: 0.0
        return PepScreeningResult(
            status = mapStatus(response.status),
            matchScore = score,
            matchedName = response.matches.firstOrNull()?.matchedName,
        )
    }

    /** Unknown / null statuses are treated as a match requiring review, never silently CLEAR. */
    private fun mapStatus(remote: String?): PepScreeningStatus = when (remote?.uppercase()) {
        "CLEAR" -> PepScreeningStatus.CLEAR
        "POTENTIAL_HIT" -> PepScreeningStatus.POTENTIAL_MATCH
        "HIT" -> PepScreeningStatus.MATCH
        "WHITELISTED" -> PepScreeningStatus.CLEAR
        else -> PepScreeningStatus.POTENTIAL_MATCH
    }

    private companion object {
        // PEP screening is name-based; entityType is informational (mirrors sanctions-service's
        // own screening convention — see openbank-domestic-payment's SanctionsScreeningAdapter).
        const val ENTITY_TYPE = "INDIVIDUAL"

        // Resilience tuning mirrors openbank-domestic-payment's SanctionsScreeningAdapter (ADR-0032 §D).
        const val CB_REQUEST_VOLUME_THRESHOLD = 4
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 10_000L
        const val CB_SUCCESS_THRESHOLD = 2
        const val RETRY_MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 300L
        const val RETRY_JITTER_MS = 150L
        const val TIMEOUT_MS = 5_000L
    }
}
