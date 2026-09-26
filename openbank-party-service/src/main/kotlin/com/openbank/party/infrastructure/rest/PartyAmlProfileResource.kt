// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.authz.Authorize
import com.openbank.party.application.port.`in`.DeclareAmlProfileCommand
import com.openbank.party.application.port.`in`.PartyAmlProfileUseCase
import com.openbank.party.domain.model.AccountPurpose
import com.openbank.party.domain.model.AmlProfileDeclaration
import com.openbank.party.domain.model.IncomeSource
import com.openbank.party.domain.model.PartyAmlProfile
import com.openbank.party.domain.model.PepCategory
import com.openbank.party.domain.model.PepDeclaration
import com.openbank.party.domain.model.TaxResidency
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The personal AML profile (AML Act 253/2008 §9 + FATCA/CRS self-certification). A separate
 * resource from [PartyResource] (already `LargeClass`), on the same base path.
 *
 * Readers: the customer edge and staff. Writer: ONLY the customer edge (or a `ROLE_API` M2M
 * caller) acting for the customer — the declaration is the customer's own statement, so a staff
 * session holding `ROLE_OPERATOR` is refused here even though `@RolesAllowed` admits the role
 * (the edge's service account carries `ROLE_OPERATOR`, and a role alone cannot tell the two
 * apart; the identity match is the load-bearing half, as in [PartyResource]'s GDPR routes). The
 * edge takes the party id from the customer's token, never from the request.
 */
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Parties")
class PartyAmlProfileResource {

    @Inject lateinit var useCase: PartyAmlProfileUseCase

    @Inject lateinit var auditPublisher: AuditEventPublisher

    @Inject @io.quarkus.arc.Unremovable
    lateinit var securityIdentity: SecurityIdentity

    @ConfigProperty(
        name = "openbank.party.gdpr.customer-edge-principal",
        defaultValue = PartyResource.DEFAULT_CUSTOMER_EDGE_PRINCIPAL,
    )
    lateinit var customerEdgePrincipal: String

    @GET
    @Path("/{id}/aml-profile")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Authorize(action = "party.amlProfile.read", resource = "#id")
    @Operation(summary = "The party's current personal AML profile (404 when none was declared)")
    suspend fun get(@PathParam("id") id: UUID): Response {
        val profile = useCase.getAmlProfile(id)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "AML_PROFILE_NOT_FOUND", "message" to "no AML profile declared for party $id"))
                .build()
        return Response.ok(profile.toResponse()).build()
    }

    @PUT
    @Path("/{id}/aml-profile")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "party.amlProfile.write", resource = "#id")
    @Operation(summary = "Declare a new version of the personal AML profile (customer edge only)")
    suspend fun put(@PathParam("id") id: UUID, req: AmlProfileRequest?): Response {
        if (!callerMayDeclare()) {
            throw ForbiddenException("the AML profile is declared by the customer, through the customer channel only")
        }
        requireNotNull(req) { "request body is required" }
        val actor = securityIdentity.principal?.name ?: "unknown"
        val profile = useCase.declareAmlProfile(DeclareAmlProfileCommand(id, req.toDeclaration(), actor))
        auditPublisher.publish(
            AuditEvent(
                actorId = actor,
                actorType = "SERVICE",
                operation = "party.aml-profile.declared",
                resourceType = "party",
                resourceId = id.toString(),
                result = AuditResult.SUCCESS,
                // The facts that drive review, never the declaration's free text or TINs.
                payload = mapOf(
                    "version" to profile.version.toString(),
                    "riskFactors" to profile.riskFactors.joinToString(",") { it.name },
                    "eddRequired" to profile.eddRequired.toString(),
                ),
            ),
        )
        return Response.ok(profile.toResponse()).build()
    }

    /** Decided from the authenticated principal and configuration only — never from request data. */
    private fun callerMayDeclare(): Boolean {
        val edge = if (this::customerEdgePrincipal.isInitialized) customerEdgePrincipal else ""
        return securityIdentity.hasRole("ROLE_API") ||
            (edge.isNotBlank() && securityIdentity.principal?.name == edge)
    }
}

data class PepRequest(
    @field:JsonProperty("isPep") @param:JsonProperty("isPep") val isPep: Boolean? = null,
    val category: String? = null,
    val detail: String? = null,
)

/**
 * `taxResidencies` is a list of ISO 3166-1 alpha-2 codes; `tin` maps a NON-CZ residency to its
 * taxpayer identification number (`{"SK": "1234567890"}`). A `tin` key that is not a declared
 * residency, or a CZ key, is a 400. The same shape comes back in the response.
 */
data class AmlProfileRequest(
    val purpose: List<String>? = null,
    val purposeNote: String? = null,
    val incomeSources: List<String>? = null,
    val incomeNote: String? = null,
    val occupation: String? = null,
    val occupationNote: String? = null,
    val expectedMonthlyTurnover: String? = null,
    val cashIntensive: Boolean? = null,
    val pep: PepRequest? = null,
    val taxResidencies: List<String?>? = null,
    val tin: Map<String, String?>? = null,
    val usPerson: Boolean? = null,
    val truthful: Boolean? = null,
) {
    fun toDeclaration(): AmlProfileDeclaration {
        val pepReq = requireNotNull(pep) { "pep is required" }
        return AmlProfileDeclaration(
            purposes = requireNotNull(purpose) {
                "purpose is required"
            }.map { it.toEnum<AccountPurpose>("purpose") }.toSet(),
            purposeNote = purposeNote.blankToNull(),
            incomeSources = requireNotNull(incomeSources) { "incomeSources is required" }
                .map { it.toEnum<IncomeSource>("incomeSources") }.toSet(),
            incomeNote = incomeNote.blankToNull(),
            occupation = occupation.toEnum("occupation"),
            occupationNote = occupationNote.blankToNull(),
            expectedMonthlyTurnover = expectedMonthlyTurnover.toEnum("expectedMonthlyTurnover"),
            cashIntensive = requireNotNull(cashIntensive) { "cashIntensive is required" },
            pep = PepDeclaration(
                isPep = requireNotNull(pepReq.isPep) { "pep.isPep is required" },
                category = pepReq.category.blankToNull()?.toEnum<PepCategory>("pep.category"),
                detail = pepReq.detail.blankToNull(),
            ),
            taxResidencies = residencies(),
            usPerson = requireNotNull(usPerson) { "usPerson is required" },
            truthful = requireNotNull(truthful) { "truthful is required" },
        )
    }

    private fun residencies(): List<TaxResidency> {
        val countries = requireNotNull(taxResidencies) { "taxResidencies is required" }
            .map { requireNotNull(it.blankToNull()) { "taxResidencies entries must be ISO country codes" }.uppercase() }
        val tins = tin.orEmpty().mapKeys { (k, _) -> k.trim().uppercase() }
        tins.keys.forEach { key ->
            require(key != AmlProfileDeclaration.DOMESTIC) { "tin is only accepted for a non-CZ tax residency" }
            require(key in countries) { "tin key '$key' is not a declared tax residency" }
        }
        return countries.map { TaxResidency(it, tins[it].blankToNull()) }
    }
}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private inline fun <reified E : Enum<E>> String?.toEnum(field: String): E {
    val v = this?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")
    return enumValues<E>().firstOrNull { it.name == v.trim().uppercase() }
        ?: throw IllegalArgumentException("unknown $field '$v'")
}

fun PartyAmlProfile.toResponse(): Map<String, Any?> {
    val d = declaration
    val facts = derivedFacts()
    return linkedMapOf(
        "partyId" to partyId,
        "version" to version,
        "declaredAt" to declaredAt,
        "purpose" to d.purposes.sorted(),
        "purposeNote" to d.purposeNote,
        "incomeSources" to d.incomeSources.sorted(),
        "incomeNote" to d.incomeNote,
        "occupation" to d.occupation,
        "occupationNote" to d.occupationNote,
        "expectedMonthlyTurnover" to d.expectedMonthlyTurnover,
        "cashIntensive" to d.cashIntensive,
        "pep" to linkedMapOf("isPep" to d.pep.isPep, "category" to d.pep.category, "detail" to d.pep.detail),
        "taxResidencies" to d.taxResidencies.map { it.country },
        "tin" to d.taxResidencies.filter { it.tin != null }.associate { it.country to it.tin },
        "usPerson" to d.usPerson,
        "truthful" to d.truthful,
        "riskFactors" to riskFactors,
        "eddRequired" to eddRequired,
        // What the app tells the customer: an EDD route means "your account needs a check".
        "reviewStatus" to if (eddRequired) "ENHANCED_DUE_DILIGENCE" else "STANDARD",
        "pepFlag" to facts.pepFlag,
        "pepCategory" to facts.pepCategory,
        "fatcaStatus" to facts.fatcaStatus,
        "crsStatus" to facts.crsStatus,
    )
}
