// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.kyc.application.port.out.CustomerScreeningPort
import com.openbank.kyc.application.port.out.CustomerScreeningRequest
import com.openbank.kyc.application.port.out.CustomerScreeningResult
import com.openbank.kyc.application.port.out.CustomerScreeningUnavailableException
import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.SanctionsScreeningOutcome
import com.openbank.kyc.application.port.out.SanctionsScreeningStatus
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resilient adapter for the CUSTOMER screening profile of `openbank-sanctions-service`.
 *
 * Every failure of the wrapped call (transport fault, exhausted retries, open circuit breaker,
 * timeout, an error status) surfaces as [CustomerScreeningUnavailableException]. Nothing here
 * turns a failure into a result, so an outage cannot be read as a clean screen.
 *
 * The response is interpreted conservatively. An unknown or missing status is a potential match.
 * A provider that does not report the PEP outcome apart (one that predates the profile and
 * ignores the field) has counted PEP names in `status`; that status is then kept as the sanctions
 * outcome, which sends the case to review instead of clearing it.
 */
@ApplicationScoped
class CustomerScreeningAdapter(@RestClient private val client: SanctionsCustomerScreeningClient) :
    CustomerScreeningPort {

    @Inject
    lateinit var self: CustomerScreeningAdapter

    // Deliberately broad, as in SanctionsScreeningAdapter: the caller must not have to enumerate
    // which failure types mean "no result".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun screenCustomer(request: CustomerScreeningRequest): CustomerScreeningResult = try {
        self.screenWithResilience(request)
    } catch (ex: Exception) {
        throw CustomerScreeningUnavailableException(ex)
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
    open suspend fun screenWithResilience(request: CustomerScreeningRequest): CustomerScreeningResult {
        val response = client.screen(
            CustomerScreenRequest(
                idempotencyKey = request.idempotencyKey,
                entityType = if (request.organisation) "ORGANIZATION" else "INDIVIDUAL",
                name = request.name,
                subjectId = request.subjectId,
                profile = PROFILE,
            ),
        ).awaitSuspending()
        val (pepMatches, decidingMatches) = response.matches.partition { it.isPep() }
        val sanctionsStatus = mapSanctions(response.status)
        return CustomerScreeningResult(
            sanctions = SanctionsScreeningOutcome(
                status = sanctionsStatus,
                matchScore = response.overallScore ?: 0.0,
                matchedName = (decidingMatches.firstOrNull() ?: pepMatches.firstOrNull())
                    ?.takeIf { sanctionsStatus != SanctionsScreeningStatus.CLEAR }?.matchedName,
            ),
            pep = PepScreeningResult(
                status = mapPep(response.pepStatus, pepMatches.isNotEmpty()),
                matchScore = pepMatches.maxOfOrNull { it.matchScore ?: 0.0 } ?: 0.0,
                matchedName = pepMatches.firstOrNull()?.matchedName,
            ),
        )
    }

    private fun CustomerScreenMatch.isPep(): Boolean = matchKind == "PEP" || listType == PEP_LIST

    /** WHITELISTED is an analyst's recorded clearance of this subject; anything unknown needs review. */
    private fun mapSanctions(remote: String?): SanctionsScreeningStatus = when (remote?.uppercase()) {
        "CLEAR", "WHITELISTED" -> SanctionsScreeningStatus.CLEAR
        "HIT" -> SanctionsScreeningStatus.MATCH
        else -> SanctionsScreeningStatus.POTENTIAL_MATCH
    }

    private fun mapPep(remote: String?, hasPepMatch: Boolean): PepScreeningStatus = when (remote?.uppercase()) {
        "CLEAR" -> PepScreeningStatus.CLEAR
        "HIT" -> PepScreeningStatus.MATCH
        "POTENTIAL_HIT" -> PepScreeningStatus.POTENTIAL_MATCH
        // No separate PEP outcome reported: fall back to the matches themselves.
        null -> if (hasPepMatch) PepScreeningStatus.MATCH else PepScreeningStatus.CLEAR
        else -> PepScreeningStatus.POTENTIAL_MATCH
    }

    private companion object {
        const val PROFILE = "CUSTOMER"
        const val PEP_LIST = "PEP_GLOBAL"

        // Resilience tuning mirrors SanctionsScreeningAdapter (ADR-0032 §D).
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
