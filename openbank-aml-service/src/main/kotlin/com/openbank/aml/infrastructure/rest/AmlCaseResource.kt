// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.ListAmlCasesQuery
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.aml.infrastructure.rest.dto.CreateAmlCaseRequest
import com.openbank.aml.infrastructure.rest.dto.UpdateAmlDecisionRequest
import com.openbank.aml.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.util.UUID

@Path("/api/v1/aml/cases")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AML Cases", description = "AML screening case lifecycle")
class AmlCaseResource(
    private val amlCaseUseCase: AmlCaseUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    // Field-injected (not a constructor parameter) so the request-scoped identity is read per call.
    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    // #10486 batch 3: ROLE_API admits the payment/FX services' OWN machine principals, which open a
    // case when a screening gate refers a transfer. ROLE_API is held by every service account, so
    // it is narrowed twice: by identity in aml_rest_ext.rego (`service-aml-case-create-m2m`) and,
    // because aml-service still runs AUTHZ_ENFORCE=false (advisory), by [requireNamedMachineCaller]
    // here, which refuses any ROLE_API-only caller not on [AML_CASE_CREATE_CALLERS].
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_API")
    @Authorize(action = "amlCase.create")
    @Operation(summary = "Submit an AML screening case")
    suspend fun createCase(
        request: CreateAmlCaseRequest,
        // Nullable by necessity: JAX-RS injects null for an absent header, and Kotlin's
        // null-safety is compile-time only — a non-nullable declaration turns a missing
        // header into a 500 (or, on a suspend fun, lets the null flow into the body).
        // libs-runtime maps IllegalArgumentException to 400 (#526, #3624).
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        requireNamedMachineCaller(identity)
        requireNotNull(idempotencyKey) { "header 'Idempotency-Key' is required" }
        require(idempotencyKey.isNotBlank()) { "Idempotency-Key header is required" }

        idempotencyStore.get(idempotencyKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Replayed", "true")
                .build()
        }

        val amlCase = amlCaseUseCase.createCase(request.toCommand(idempotencyKey))
        val responseBody = amlCase.toResponse()
        idempotencyStore.save(idempotencyKey, 201, objectMapper.writeValueAsString(responseBody))

        return Response.created(URI.create("/api/v1/aml/cases/${amlCase.id}"))
            .entity(responseBody)
            .build()
    }

    @GET
    @Path("/{caseId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_API")
    @Authorize(action = "amlCase.read", resource = "#caseId")
    @Operation(summary = "Get AML case by ID")
    suspend fun getCase(@PathParam("caseId") caseId: UUID): Response =
        Response.ok(amlCaseUseCase.getCase(caseId).toResponse()).build()

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_API")
    @Authorize(action = "amlCase.list")
    @Operation(summary = "List AML screening cases")
    suspend fun listCases(
        @QueryParam("status") status: String?,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("screeningType") screeningType: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): Response {
        val amlCases = amlCaseUseCase.listCases(
            ListAmlCasesQuery(
                status = status?.let(AmlCaseStatus::valueOf),
                partyId = partyId,
                screeningType = screeningType?.let(ScreeningType::valueOf),
                limit = limit,
                offset = offset,
            ),
        )
        return Response.ok(amlCases.map { it.toResponse() }).build()
    }

    @PUT
    @Path("/{caseId}/decision")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "amlCase.updateDecision", resource = "#caseId")
    @Operation(summary = "Update AML case decision")
    suspend fun updateDecision(@PathParam("caseId") caseId: UUID, request: UpdateAmlDecisionRequest): Response =
        Response.ok(amlCaseUseCase.updateDecision(request.toCommand(caseId)).toResponse()).build()
}

/** Staff roles `createCase` admitted before #10486; a caller holding one needs no identity check. */
private val AML_CASE_CREATE_STAFF_ROLES = setOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")

/**
 * #10486 batch 3: the machine principals that open AML cases, each authenticating as its OWN
 * Keycloak client (ROLE_API only). Mirrors `service-aml-case-create-m2m` in aml_rest_ext.rego; the
 * two must list the same principals (`AmlCaseCreateCallerGuardTest` pins this set).
 */
internal val AML_CASE_CREATE_CALLERS = setOf(
    "service-account-openbank-domestic-payment",
    "service-account-openbank-sepa-payment",
    "service-account-openbank-sepa-instant",
    "service-account-openbank-fx",
)

/**
 * A caller that reached `createCase` through ROLE_API alone must be one of [AML_CASE_CREATE_CALLERS].
 * This is the enforcing half while aml-service runs OPA advisory: without it, ROLE_API — held by
 * every service account in the realm — would let any of them open a case.
 */
internal fun requireNamedMachineCaller(identity: SecurityIdentity) {
    if (AML_CASE_CREATE_STAFF_ROLES.any(identity::hasRole)) return
    if (identity.principal?.name !in AML_CASE_CREATE_CALLERS) {
        throw ForbiddenException("caller is not a named AML case-open service")
    }
}
