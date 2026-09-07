// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.processor

import com.openbank.cardprocessing.application.port.`in`.AuthorizationCommand
import com.openbank.cardprocessing.application.port.`in`.CardProcessingUseCase
import com.openbank.cardprocessing.application.port.`in`.PresentmentCommand
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.domain.model.PresentmentOutcome
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * The sandbox acquirer: the binding of the processor port that this repository can actually run.
 *
 * ## What it is for
 *
 * A licensed issuer-processor is a certification programme, not a reference-architecture deliverable
 * (ADR-0283 D2, inherited from ADR-0190). Everything on **our** side of that boundary is buildable
 * and testable today, and until something presents authorisations there is nothing to test it with
 * — which is exactly how card-issuance's decision point came to have no caller at all. This endpoint
 * is that something: it shapes an ISO 8583-style flow (an authorisation, then a presentment) as
 * JSON and drives the same use case a real processor adapter would.
 *
 * ## Why it is off unless switched on
 *
 * It can move money end to end, so it is guarded by
 * `openbank.card-processing.sandbox-acquirer-enabled`, **default false**, and it answers 404 when
 * disabled rather than 403: a disabled simulator should be indistinguishable from one that was
 * never deployed, so a probe cannot enumerate it.
 *
 * The flag is read per request, not at construction: `@ApplicationScoped` is lazy, so a
 * constructor-time guard would not run until the first call anyway — the same laziness that kept a
 * boot-time warning out of every pod log for the life of a service (#1299).
 */
@Path("/api/v1/sandbox/acquirer")
@Produces(MediaType.APPLICATION_JSON)
class SandboxAcquirerResource(
    private val useCase: CardProcessingUseCase,
    @ConfigProperty(name = "openbank.card-processing.sandbox-acquirer-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {

    /**
     * A card presented at a merchant: authorise, then present for clearing in one call.
     *
     * The two steps stay separate underneath — a real purchase authorises at the till and clears one
     * to three days later, and collapsing them in the domain would hide every defect that lives in
     * the gap (an expiring hold, a partial presentment, a reversal).
     */
    @POST
    @Path("/purchase")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "cardprocessing.simulate", resource = "")
    @Operation(summary = "Sandbox: present a purchase (authorisation, then optional clearing)")
    suspend fun purchase(request: SandboxPurchaseRequest): Response {
        if (!enabled) return Response.status(Response.Status.NOT_FOUND).build()
        // The caller's key IS the network reference here: a real acquirer identifies its own
        // message, and a replay of that message must not take a second hold. Required in the body
        // rather than as a header because that is what a scheme message looks like — the two
        // idioms are equivalent to the coverage gate, and this one matches the domain.
        val reference = request.idempotencyKey
        val authorization = useCase.authorize(
            AuthorizationCommand(
                cardId = request.cardId,
                amountMinorUnits = request.amountMinorUnits,
                currencyCode = request.currencyCode,
                channel = request.channel,
                mcc = request.mcc,
                merchantName = request.merchantName,
                merchantCountry = request.merchantCountry,
                networkReference = reference,
                idempotencyKey = reference,
            ),
        )
        if (authorization.declineReason != null || !request.clearImmediately) {
            return Response.ok(SandboxPurchaseResponse.of(authorization, reference)).build()
        }
        val cleared = useCase.clear(
            PresentmentCommand(
                authorizationId = authorization.id,
                amountMinorUnits = request.clearingAmountMinorUnits ?: request.amountMinorUnits,
                currencyCode = request.currencyCode,
                idempotencyKey = "$reference:clearing",
            ),
        )
        val settled = (cleared as? PresentmentOutcome.Accepted)?.authorization ?: authorization
        return Response.ok(SandboxPurchaseResponse.of(settled, reference)).build()
    }
}

data class SandboxPurchaseRequest(
    /** The acquirer's own message reference. Required: a replayed purchase must not double-hold. */
    val idempotencyKey: String,
    val cardId: UUID,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val channel: PresentmentChannel,
    val mcc: String? = null,
    val merchantName: String? = null,
    val merchantCountry: String? = null,
    val clearImmediately: Boolean = true,
    /** A partial presentment, as a fuel or hospitality merchant would send. Defaults to the full amount. */
    val clearingAmountMinorUnits: Long? = null,
)

data class SandboxPurchaseResponse(
    val authorizationId: UUID,
    val status: String,
    val declineReason: String?,
    val clearedAmountMinorUnits: Long,
    val networkReference: String,
) {
    companion object {
        fun of(authorization: com.openbank.cardprocessing.domain.model.CardAuthorization, reference: String) =
            SandboxPurchaseResponse(
                authorizationId = authorization.id,
                status = authorization.status.name,
                declineReason = authorization.declineReason,
                clearedAmountMinorUnits = authorization.clearedAmountMinorUnits,
                networkReference = reference,
            )
    }
}
