// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.lending

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.campaign.application.port.out.CreditOfferGatePort
import com.openbank.campaign.application.port.out.CreditOfferGateUnavailableException
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

@RegisterRestClient(configKey = "lending-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/lending/credit-offers")
@Produces(MediaType.APPLICATION_JSON)
interface CreditOfferEligibilityClient {
    @GET
    @Path("/eligibility/{partyId}")
    @Timeout(ELIGIBILITY_TIMEOUT_MS)
    fun eligibility(@PathParam("partyId") partyId: UUID): Uni<CreditOfferEligibilityResponse>
}

/** Only what this service acts on. `policyVersion` is deliberately not read here — see below. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CreditOfferEligibilityResponse(val allowed: Boolean = false, val reasonCode: String? = null)

/** A short budget: this sits in the send path, and a slow refusal must not hold a journey open. */
const val ELIGIBILITY_TIMEOUT_MS = 3_000L

/**
 * ADR-0269 rule 2, asked of lending-service (#8918, route added in #8956).
 *
 * ## Nothing is sent on failure — but an outage is not a suppression
 *
 * An unreachable floor raises [CreditOfferGateUnavailableException] rather than answering `false`.
 * Both stop the send; only one of them is honest. Recording a distress suppression because
 * lending-service timed out would write a conduct fact about a party who may be perfectly healthy,
 * consume the step permanently instead of letting the journey retry, and corrupt the metric that
 * exists to answer "how many people did we decline to offer credit to, and why". This service
 * already draws that line for the contact gate; this follows it rather than inventing a second
 * convention.
 *
 * `allowed` still defaults to `false` on the DTO, so a 200 whose body omits the field cannot
 * deserialise into permission.
 *
 * ## Why the reason code is logged but not returned
 *
 * The caller has one decision to make and needs one bit to make it. The code says WHY, which
 * belongs in the log and in lending's own metrics — handing it back would invite a caller to branch
 * on individual codes and, sooner or later, to treat one of them as benign.
 */
@ApplicationScoped
class HttpCreditOfferGate(@param:RestClient private val client: CreditOfferEligibilityClient) : CreditOfferGatePort {

    private val log = Logger.getLogger(HttpCreditOfferGate::class.java)

    override suspend fun mayOffer(partyId: UUID): Boolean = try {
        val decision = client.eligibility(partyId).awaitSuspending()
        if (!decision.allowed) {
            log.infof("credit offer suppressed for party=%s reason=%s", partyId, decision.reasonCode ?: "unstated")
        }
        decision.allowed
    } catch (e: CancellationException) {
        // Rethrown, never swallowed: a cancelled coroutine reported as "not allowed" would be a
        // business decision invented from a shutdown, and would read in the metrics as a real
        // suppression that never happened.
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // TooGenericExceptionCaught is the point: every failure means the same thing here — the
        // bank cannot tell whether it may offer — and narrowing this would let some unanticipated
        // fault through as permission.
        log.warnf(e, "credit offer gate unreachable for party=%s; refusing to send, will retry", partyId)
        throw CreditOfferGateUnavailableException("credit offer gate unavailable for party $partyId", e)
    }
}
