// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.web.SyntheticTaintExternalBoundary
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Visa Developer Platform, BIN attributes (ADR-0283 phase 2, #8810).
 *
 * ## Authentication is CONFIGURATION, not code
 *
 * Visa's sandbox takes two-way TLS plus an API key. The mutual TLS half is
 * `quarkus.rest-client."visa-api".{key-store,trust-store}` in `application.yaml` — Quarkus performs
 * the handshake, so there is no crypto here to get wrong. The key travels as the `apikey` header
 * below. Both come from OpenBao and neither is committed; a build with no credentials leaves the
 * adapter disabled rather than half-configured.
 *
 * ## No `SyntheticTaintClientFilter` here, deliberately
 *
 * This is the one client in the service that LEAVES the platform. The ADR-0252 taint marks
 * bank-owned synthetic traffic for our own downstreams; forwarding it to Visa would export an
 * internal marker to a third party, and Visa has nothing to do with it. The class is annotated
 * `SyntheticTaintExternalBoundary` so the gate records that as a decision rather than an omission.
 */
@Path("/paai/fundstransfer/v1")
@RegisterRestClient(configKey = "visa-api")
@SyntheticTaintExternalBoundary("Visa is a third party; the ADR-0252 taint must not leave the platform")
@Produces(MediaType.APPLICATION_JSON)
interface VisaSchemeClient {

    /**
     * The path is a LITERAL, matching what Visa documents, and is not derived from anything.
     *
     * A vendor route cannot be adjudicated by a provider replay the way an internal one can — there
     * is no Visa-side test in this repository — so the only protection against a wrong path is that
     * it is written once, next to the vendor's own name for the product, and reviewed as text.
     */
    @GET
    @Path("/binlookup/{bin}")
    suspend fun binAttributes(@PathParam("bin") bin: String, @HeaderParam("apikey") apiKey: String): VisaBinResponse
}

/**
 * Only the fields the port needs. Visa returns considerably more; ignoring the rest is what stops
 * an unrelated addition on their side from breaking this adapter.
 */
data class VisaBinResponse(
    val binNumber: String? = null,
    val cardBrand: String? = null,
    val cardType: String? = null,
    val fundingSource: String? = null,
    val issuerName: String? = null,
    val issuerCountryCode: String? = null,
)

/**
 * Maps a vendor HTTP status onto a [SchemeFailure] a caller can branch on.
 *
 * Shared by both vendor adapters because the mapping is a property of HTTP, not of the vendor, and
 * two copies would drift. The distinctions are the ones a caller acts on differently: 401/403 will
 * not fix itself by retrying, 404 is a real answer, everything else is worth another attempt.
 */
internal fun httpStatusToFailure(status: Int): SchemeFailure = when (status) {
    HTTP_NOT_FOUND -> SchemeFailure.NOT_FOUND
    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SchemeFailure.UNAUTHENTICATED
    else -> SchemeFailure.UNAVAILABLE
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
