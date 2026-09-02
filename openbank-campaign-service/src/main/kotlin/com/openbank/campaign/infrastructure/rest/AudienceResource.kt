// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.AudienceService
import com.openbank.campaign.domain.model.SegmentRule
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken

/** A closed, separately approved audience lifecycle; never a free-form query endpoint. */
@Path("/api/v1/audiences")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
class AudienceResource(private val service: AudienceService, private val jwt: JsonWebToken) {

    @GET
    @Authorize(action = "campaign.read", resource = "")
    suspend fun list(): Response = Response.ok(service.list()).build()

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "campaign.create", resource = "")
    suspend fun create(request: CreateAudienceRequest): Response = try {
        Response.status(Response.Status.CREATED)
            .entity(service.summary(service.create(request.name, request.toRules(), jwt.audiencePrincipal())))
            .build()
    } catch (e: IllegalArgumentException) {
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("error" to e.message)).build()
    }

    @POST
    @Path("/{name}/{version}/submit")
    @Authorize(action = "campaign.submit", resource = "#name")
    suspend fun submit(@PathParam("name") name: String, @PathParam("version") version: Int): Response =
        lifecycle { service.summary(service.submit(name, version, jwt.audiencePrincipal())) }

    @POST
    @Path("/{name}/{version}/approve")
    @Authorize(action = "campaign.activate", resource = "#name@#version")
    suspend fun approve(@PathParam("name") name: String, @PathParam("version") version: Int): Response =
        lifecycle { service.summary(service.approve(name, version, jwt.audiencePrincipal())) }

    @GET
    @Path("/{name}/{version}/preview")
    @Authorize(action = "campaign.read", resource = "#name")
    suspend fun preview(@PathParam("name") name: String, @PathParam("version") version: Int): Response =
        service.preview(name, version)?.let { Response.ok(it).build() }
            ?: Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to "unknown audience")).build()

    private suspend fun lifecycle(action: suspend () -> Any): Response = try {
        Response.ok(action()).build()
    } catch (_: NoSuchElementException) {
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to "unknown audience")).build()
    } catch (e: IllegalArgumentException) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
    }
}

data class CreateAudienceRequest(
    val name: String,
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of
     * a collection, so `{"rules": [null]}` deserialises happily into a `List<AudienceRuleRequest>`
     * holding a null. Writing the type honestly is what makes the guard in [toRules] reachable
     * instead of dead code.
     */
    val rules: List<AudienceRuleRequest?>,
) {
    /**
     * The only production read of [rules]. `IllegalArgumentException` is answered as 400 by this
     * resource's own catch, so no service-local `ExceptionMapper` is added (#526).
     */
    fun toRules(): List<SegmentRule> = rules.mapIndexed { index, rule ->
        requireNotNull(rule) { "rules[$index] must not be null" }.toRule()
    }
}

/** The wire rule vocabulary is deliberately smaller than the domain's unsupported rule set. */
data class AudienceRuleRequest(val type: AudienceRuleType, val status: String? = null, val minDays: Long? = null) {
    fun toRule(): SegmentRule = when (type) {
        AudienceRuleType.PARTY_STATUS_IS -> SegmentRule.PartyStatusIs(
            requireNotNull(status) {
                "PARTY_STATUS_IS requires status"
            },
        )
        AudienceRuleType.TENURE_AT_LEAST_DAYS -> SegmentRule.TenureAtLeastDays(
            requireNotNull(minDays) {
                "TENURE_AT_LEAST_DAYS requires minDays"
            },
        )
    }
}

enum class AudienceRuleType { PARTY_STATUS_IS, TENURE_AT_LEAST_DAYS }

private fun JsonWebToken.audiencePrincipal(): String = name ?: subject ?: "unknown"
