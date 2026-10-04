// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.web.SyntheticTaintExternalBoundary
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Mastercard Developers, BIN lookup (ADR-0283 phase 2, #8810).
 *
 * ## Why the Authorization header is a parameter and not a filter
 *
 * Mastercard authenticates with **OAuth 1.0a request signing**, not a bearer token: the header is
 * an RSA-SHA256 signature over the request's own method, URL and query parameters. It therefore
 * cannot be attached by a generic client filter that does not know them — the caller computes it
 * per request and passes it in ([MastercardOAuthSigner]).
 *
 * That is worth stating because the wrong instinct is to reach for `@OidcClientFilter` or a static
 * header, which produce a signature that is valid-looking and always rejected — a 401 that reads
 * like a credential problem when it is a construction problem.
 *
 * ## No taint propagation, deliberately
 *
 * Same reasoning as the Visa client: this edge leaves the platform.
 */
@Path("/bin-resources")
@RegisterRestClient(configKey = "mastercard-api")
@SyntheticTaintExternalBoundary("Mastercard is a third party; the ADR-0252 taint must not leave the platform")
@Produces(MediaType.APPLICATION_JSON)
interface MastercardSchemeClient {

    /** LITERAL path, matching what Mastercard documents. See the Visa client for why. */
    @GET
    @Path("/bin-ranges/account-searches")
    suspend fun binLookup(
        @QueryParam("accountRange") accountRange: String,
        @HeaderParam("Authorization") oauthSignature: String,
    ): MastercardBinResponse
}

/** Only the fields the port needs; Mastercard returns more. */
data class MastercardBinResponse(
    val lowAccountRange: String? = null,
    val brandProductCode: String? = null,
    val brandProductName: String? = null,
    val fundingSource: String? = null,
    val customerName: String? = null,
    val country: String? = null,
)
