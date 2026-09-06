// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.party.application.port.`in`.GrantMandateCommand
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.application.port.`in`.RevokeMandateCommand
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.PartyMandate
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Representation mandates (ADR-0284 D3): who may act for a legal-entity party. A separate
 * resource from [PartyResource] (already `LargeClass`), on the same base path.
 *
 * Writers are kyb-service (a signed business case) and back-office staff; readers are the
 * customer edge (`acting-for`, the profile switcher) and staff. The edge never writes a mandate:
 * a mandate is a fact about the register, not a customer preference.
 */
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Parties")
class PartyMandateResource {

    @Inject lateinit var partyUseCase: PartyUseCase

    @POST
    @Path("/{id}/mandates")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Authorize(action = "party.mandate.grant", resource = "#id")
    @Operation(summary = "Grant (or refresh) a representation mandate: agent may act for the entity party {id}")
    suspend fun grant(@PathParam("id") id: UUID, req: GrantMandateRequest?): Response {
        requireNotNull(req) { "request body is required" }
        val mandate = partyUseCase.grantMandate(
            GrantMandateCommand(
                principalPartyId = id,
                agentPartyId = requireNotNull(req.agentPartyId) { "agentPartyId is required" },
                role = req.role.toEnum<MandateRole>("role"),
                authority = (req.authority ?: "SOLE").toEnum<MandateAuthority>("authority"),
                source = (req.source ?: "MANUAL").toEnum<MandateSource>("source"),
                evidenceRef = req.evidenceRef,
                validTo = req.validTo,
            ),
        )
        return Response.created(
            URI.create("/api/v1/parties/$id/mandates/${mandate.id}"),
        ).entity(mandate.toResponse()).build()
    }

    @GET
    @Path("/{id}/mandates")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Authorize(action = "party.mandate.read", resource = "#id")
    @Operation(summary = "Every mandate over the entity party {id}, active or not")
    suspend fun list(@PathParam("id") id: UUID): Response =
        Response.ok(partyUseCase.listMandates(id).map { it.toResponse() }).build()

    @DELETE
    @Path("/{id}/mandates/{mandateId}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC")
    @Authorize(action = "party.mandate.revoke", resource = "#id")
    @Operation(summary = "Revoke a mandate (register change, resignation, power of attorney withdrawn)")
    suspend fun revoke(
        @PathParam("id") id: UUID,
        @PathParam("mandateId") mandateId: UUID,
        req: RevokeMandateRequest?,
    ): Response {
        val reason = requireNotNull(req?.reason?.takeIf { it.isNotBlank() }) { "reason is required" }
        return Response.ok(partyUseCase.revokeMandate(RevokeMandateCommand(id, mandateId, reason)).toResponse()).build()
    }

    /**
     * The profile switcher's source of truth (ADR-0284 D4): the entities the human {id} may
     * currently act for. Path-scoped to the agent, so the edge can only ever ask about the
     * party whose token it validated.
     */
    @GET
    @Path("/{id}/acting-for")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Authorize(action = "party.mandate.read", resource = "#id")
    @Operation(summary = "Entities the natural person {id} holds an ACTIVE mandate for")
    suspend fun actingFor(@PathParam("id") id: UUID): Response = Response.ok(
        partyUseCase.actingFor(id).map { p ->
            mapOf(
                "partyId" to p.party.id,
                "partyType" to p.party.partyType,
                "legalName" to p.party.legalName,
                "tradingName" to p.party.tradingName,
                "status" to p.party.status,
                "kycStatus" to p.party.kycStatus,
                "registrationNumber" to p.party.registrationNumber,
                "registrationCountry" to p.party.registrationCountry,
                "legalForm" to p.party.legalForm,
                "mandate" to p.mandate.toResponse(),
            )
        },
    ).build()

    private inline fun <reified E : Enum<E>> String?.toEnum(field: String): E {
        val v = this?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")
        return enumValues<E>().firstOrNull { it.name == v.uppercase() }
            ?: throw IllegalArgumentException("unknown $field '$v'")
    }
}

data class GrantMandateRequest(
    val agentPartyId: UUID?,
    val role: String?,
    val authority: String? = null,
    val source: String? = null,
    val evidenceRef: String? = null,
    val validTo: Instant? = null,
)

data class RevokeMandateRequest(val reason: String?)

fun PartyMandate.toResponse() = mapOf(
    "id" to id,
    "principalPartyId" to principalPartyId,
    "agentPartyId" to agentPartyId,
    "role" to role,
    "authority" to authority,
    "source" to source,
    "status" to status,
    "evidenceRef" to evidenceRef,
    "validFrom" to validFrom,
    "validTo" to validTo,
    "revokedAt" to revokedAt,
    "revokeReason" to revokeReason,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
)
