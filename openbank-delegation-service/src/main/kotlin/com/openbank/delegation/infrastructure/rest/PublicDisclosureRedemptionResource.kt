// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.PublicDisclosureRedemptionUseCase
import com.openbank.delegation.application.usecase.DisclosureRedemptionInvalidException
import com.openbank.delegation.infrastructure.rest.dto.DownloadDisclosureRequest
import com.openbank.delegation.infrastructure.rest.dto.VerifyDisclosureRedemptionRequest
import com.openbank.delegation.infrastructure.rest.dto.VerifyDisclosureRedemptionResponse
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/public/disclosures")
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
class PublicDisclosureRedemptionResource(private val redemption: PublicDisclosureRedemptionUseCase) {
    @POST
    @Path("/verify")
    @Produces(MediaType.APPLICATION_JSON)
    suspend fun verify(
        request: VerifyDisclosureRedemptionRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response = concealInvalid {
        requireNotNull(idempotencyKey) { "Idempotency-Key header is required" }
        Response.ok(
            VerifyDisclosureRedemptionResponse(
                redemption.verify(request.magicToken, request.otp, idempotencyKey),
            ),
        )
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .build()
    }

    @POST
    @Path("/content")
    @Produces("application/pdf")
    suspend fun content(
        request: DownloadDisclosureRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response = concealInvalid {
        requireNotNull(idempotencyKey) { "Idempotency-Key header is required" }
        val result = redemption.download(request.accessTicket, idempotencyKey)
        Response.ok(result.content, "application/pdf")
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(HttpHeaders.ETAG, "\"${result.sha256}\"")
            .header("X-Disclosure-View", "${result.viewNumber}/${result.maxViews}")
            .header("Content-Disposition", "inline; filename=disclosure.pdf")
            .build()
    }

    private suspend fun concealInvalid(block: suspend () -> Response): Response = try {
        block()
    } catch (_: DisclosureRedemptionInvalidException) {
        throw NotFoundException()
    }

    private companion object {
        const val NO_STORE = "private, no-store, max-age=0"
    }
}
