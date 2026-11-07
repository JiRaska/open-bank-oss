// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.party.application.port.`in`.PartyAmlProfileUseCase
import com.openbank.party.application.port.`in`.PartyUseCase
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant
import java.util.UUID

/**
 * The CRS/FATCA TIN boundary (ADR-0340 B1): the ONE route through which taxpayer identification
 * numbers leave party-service, and it exists for exactly one reader —
 * `service-account-openbank-tax-reporting`, the statutory-reporting assembly.
 *
 * Why a dedicated resource and not the existing `aml-profile` read: that route is a staff/customer
 * read whose audience is a person reviewing a file. Tax reporting is a bulk, cross-customer M2M
 * assembly, so it gets its own action (`party.reporting.taxFacts` — deliberately not a read/list
 * verb, so no generic read rule accidentally admits anyone), an in-code principal guard on top of
 * `@RolesAllowed`/`@Authorize`, and an audit event whose payload carries the derived statuses but
 * NEVER the TINs themselves. The response body does carry TINs — that is its whole purpose — so
 * the route must be unreachable for anyone but the reporting service account.
 *
 * The caller (tax-reporting-service) already holds the party's account roster, so a TIN leak here
 * is bounded to parties it can also enumerate — but the guard is not the only control:
 * `PartyTaxReportingResourceIT` drives the route over real HTTP for both the allowed and the
 * denied caller.
 */
@Path("/api/v1/reporting/parties")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Parties")
class PartyTaxReportingResource {

    @Inject lateinit var partyUseCase: PartyUseCase

    @Inject lateinit var amlProfileUseCase: PartyAmlProfileUseCase

    @Inject lateinit var auditPublisher: AuditEventPublisher

    @Inject @io.quarkus.arc.Unremovable
    lateinit var securityIdentity: SecurityIdentity

    @GET
    @Path("/{id}/tax-facts")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "party.reporting.taxFacts", resource = "#id")
    @Operation(
        summary = "Party tax facts for statutory reporting (tax-reporting-service only; TINs included, audit-logged)",
    )
    suspend fun taxFacts(@PathParam("id") id: UUID): Response {
        // The @Authorize action is also gated in rego; this guard is the explicit in-code twin so a
        // misconfigured bundle fails closed here instead of leaking TINs. isPrincipal checks the
        // VERIFIED service-account claims (azp bound to the service-account username, #12448), never
        // the principal name alone.
        if (!ServiceAccountIdentity.isPrincipal(securityIdentity, TAX_REPORTING_PRINCIPAL)) {
            throw ForbiddenException("tax facts are readable by $TAX_REPORTING_PRINCIPAL only")
        }
        val principal = ServiceAccountIdentity.verifiedPrincipalName(securityIdentity) ?: "unknown"
        val party = partyUseCase.getParty(id)
        val profile = amlProfileUseCase.getAmlProfile(id)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(
                    mapOf(
                        "error" to "TAX_PROFILE_NOT_FOUND",
                        "message" to "no tax residency profile declared for party $id",
                    ),
                )
                .build()
        val facts = profile.derivedFacts()
        auditPublisher.publish(
            AuditEvent(
                actorId = principal,
                actorType = "SERVICE",
                operation = "party.tax-facts.reporting-read",
                resourceType = "party",
                resourceId = id.toString(),
                result = AuditResult.SUCCESS,
                // Derived statuses only — the payload NEVER carries the TINs that are in the body.
                payload = mapOf(
                    "profileVersion" to profile.version.toString(),
                    "crsStatus" to facts.crsStatus.name,
                    "fatcaStatus" to facts.fatcaStatus.name,
                    "residencyCountries" to profile.declaration.taxResidencies.joinToString(",") { it.country },
                ),
            ),
        )
        return Response.ok(
            PartyTaxFactsResponse(
                partyId = id,
                holderName = party.legalName,
                profileVersion = profile.version,
                declaredAt = profile.declaredAt,
                crsStatus = facts.crsStatus.name,
                fatcaStatus = facts.fatcaStatus.name,
                taxResidencies = profile.declaration.taxResidencies.map {
                    TaxResidencyFact(country = it.country, tin = it.tin)
                },
            ),
        ).build()
    }

    companion object {
        const val TAX_REPORTING_PRINCIPAL = "service-account-openbank-tax-reporting"
    }
}

data class TaxResidencyFact(val country: String, val tin: String?)

data class PartyTaxFactsResponse(
    val partyId: UUID,
    val holderName: String,
    val profileVersion: Int,
    val declaredAt: Instant,
    val crsStatus: String,
    val fatcaStatus: String,
    val taxResidencies: List<TaxResidencyFact>,
)
