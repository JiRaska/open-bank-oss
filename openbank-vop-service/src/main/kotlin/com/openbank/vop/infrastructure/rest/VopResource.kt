// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.vop.application.port.`in`.VerifyPayeeCommand
import com.openbank.vop.application.port.`in`.VerifyPayeeUseCase
import com.openbank.vop.infrastructure.rest.dto.VerifyPayeeRequest
import com.openbank.vop.infrastructure.rest.dto.VerifyPayeeResponse
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Verification of Payee (ADR-0171, IPR Art. 5c).
 *
 * Note this is a POST, not a GET, despite being a read: the IBAN and payee name are personal data
 * and must never land in a URL, an access log, or a referer header.
 */
@Path("/api/v1/vop")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "Verification of Payee",
    description = "Name-vs-IBAN verification before a credit transfer (ADR-0171, Reg. (EU) 2024/886 Art. 5c)",
)
class VopResource(private val verifyPayee: VerifyPayeeUseCase) {

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * `vop.verify` — deliberately NOT `vop.create`. This mints no resource; it is a check. The
     * action prefix matches the module name `openbank-vop-service`, so `money_path_scopes` in the
     * base `rest.rego` derives "vop" and actually matches — unlike sepa-instant, whose
     * `sctInstPayment` prefix silently never fires the four-eyes rule (see
     * `gen-sepa-instant-opa-bundle.sh`).
     */
    @POST
    @Path("/verify")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "vop.verify")
    @Operation(
        summary = "Verify a payee name against an IBAN",
        description = "Returns match | close_match | no_match | no_data. The account-holder name is " +
            "disclosed only on close_match, never on no_match.",
    )
    fun verify(request: VerifyPayeeRequest): Uni<Response> {
        val valid = request.validated()
        return verifyPayee.verify(
            VerifyPayeeCommand(
                iban = valid.creditorIban,
                payeeName = valid.creditorName,
                requestedBy = requesterId(),
            ),
        ).map { verification -> Response.ok(VerifyPayeeResponse.of(verification)).build() }
    }

    // .principal.name (preferred_username), NOT .subject (UUID) — matches how
    // AuthorizeInterceptor.buildQuery resolves Principal.id, so the evidence row's requestedBy and
    // the OPA decision refer to the same identity in the same format.
    private fun requesterId(): String = identity.principal?.name ?: "anonymous"
}
