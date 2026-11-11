// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.kyb.application.port.out.RelatedPartyScreeningPort
import com.openbank.kyb.application.port.out.RelatedPartyScreeningRequest
import com.openbank.kyb.application.port.out.RelatedPartyScreeningResult
import com.openbank.kyb.application.port.out.RelatedPartyScreeningUnavailableException
import com.openbank.kyb.domain.model.ScreeningVerdict
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Reactive REST client for `openbank-sanctions-service`'s v3 screen endpoint, which carries the
 * explicit screening profile. The bearer is the service token minted by the default oidc-client.
 */
@RegisterRestClient(configKey = "sanctions-service")
// Below @RegisterRestClient deliberately (see PartyServiceRestClient): sanctions-service is an
// internal edge and must PROPAGATE the synthetic marker, so a synthetic case's screens stay tainted.
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v3/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsScreeningRestClient {

    @POST
    @Path("/screen")
    fun screen(request: SanctionsScreenBody): Uni<SanctionsScreenResponse>
}

/** Mirror of sanctions-service's v3 screen command, limited to what a related-party screen sends. */
data class SanctionsScreenBody(
    val idempotencyKey: String,
    val entityType: String,
    val name: String,
    val subjectId: String,
    val profile: String,
)

/** Subset of the sanctions-service check payload this service acts on. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SanctionsScreenResponse(
    val status: String? = null,
    val pepStatus: String? = null,
    val matches: List<SanctionsScreenMatch> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SanctionsScreenMatch(
    val listType: String? = null,
    val matchKind: String? = null,
    val matchedName: String? = null,
)

/**
 * Resilient adapter for the CUSTOMER screening profile of `openbank-sanctions-service`.
 *
 * Every failure of the wrapped call (transport fault, exhausted retries, open circuit breaker,
 * timeout, an error status) surfaces as [RelatedPartyScreeningUnavailableException]. Nothing here
 * turns a failure into a result, so an outage cannot be read as a clean screen.
 *
 * The response is read conservatively. An unknown or missing status is a potential hit. A provider
 * that does not report the PEP outcome apart (one that predates the profile and ignores the field)
 * has counted PEP names in `status`; that status is then kept as the sanctions outcome, which
 * sends the case to review instead of clearing it.
 *
 * The `self` injection is load-bearing: fault-tolerance interceptors only fire through the CDI
 * proxy, so calling [screenWithResilience] on `this` would silently bypass all three annotations.
 */
@ApplicationScoped
class SanctionsRelatedPartyScreeningAdapter(@RestClient private val client: SanctionsScreeningRestClient) :
    RelatedPartyScreeningPort {

    @Inject
    lateinit var self: SanctionsRelatedPartyScreeningAdapter

    // Deliberately broad: the caller must not have to enumerate which failure types mean "no result".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun screen(request: RelatedPartyScreeningRequest): RelatedPartyScreeningResult = try {
        self.screenWithResilience(request)
    } catch (ex: Exception) {
        throw RelatedPartyScreeningUnavailableException(ex)
    }

    @CircuitBreaker(
        requestVolumeThreshold = CB_REQUEST_VOLUME_THRESHOLD,
        failureRatio = CB_FAILURE_RATIO,
        delay = CB_DELAY_MS,
        successThreshold = CB_SUCCESS_THRESHOLD,
    )
    @Retry(maxRetries = RETRY_MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS, retryOn = [Exception::class])
    @Timeout(TIMEOUT_MS)
    open suspend fun screenWithResilience(request: RelatedPartyScreeningRequest): RelatedPartyScreeningResult {
        val response = client.screen(
            SanctionsScreenBody(
                idempotencyKey = request.idempotencyKey,
                entityType = if (request.organisation) "ORGANIZATION" else "INDIVIDUAL",
                name = request.name,
                subjectId = request.subjectId,
                profile = PROFILE,
            ),
        ).awaitSuspending()
        val (pepMatches, decidingMatches) = response.matches.partition { it.isPep() }
        val sanctions = mapSanctions(response.status)
        return RelatedPartyScreeningResult(
            sanctions = sanctions,
            pep = isPep(response.pepStatus, pepMatches.isNotEmpty()),
            matchedName = (decidingMatches.firstOrNull() ?: pepMatches.firstOrNull())
                ?.takeIf { sanctions != ScreeningVerdict.CLEAR || pepMatches.isNotEmpty() }?.matchedName,
        )
    }

    private fun SanctionsScreenMatch.isPep(): Boolean = matchKind == "PEP" || listType == PEP_LIST

    /** WHITELISTED is an analyst's recorded clearance of this subject; anything unknown needs review. */
    private fun mapSanctions(remote: String?): ScreeningVerdict = when (remote?.uppercase()) {
        "CLEAR", "WHITELISTED" -> ScreeningVerdict.CLEAR
        "HIT" -> ScreeningVerdict.HIT
        else -> ScreeningVerdict.POTENTIAL_HIT
    }

    /** Only an explicit CLEAR with no PEP match is "not a PEP"; an unknown PEP status counts as one. */
    private fun isPep(remote: String?, hasPepMatch: Boolean): Boolean = when (remote?.uppercase()) {
        "CLEAR" -> hasPepMatch
        // No separate PEP outcome reported: fall back to the matches themselves.
        null -> hasPepMatch
        else -> true
    }

    private companion object {
        const val PROFILE = "CUSTOMER"
        const val PEP_LIST = "PEP_GLOBAL"

        // Resilience tuning mirrors the fleet's other sanctions adapters (ADR-0032 §D).
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
