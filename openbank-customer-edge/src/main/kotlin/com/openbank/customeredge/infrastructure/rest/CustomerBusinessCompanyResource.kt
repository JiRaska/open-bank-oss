// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The company behind the business profile the app is switched to (ADR-0284 D4/D6): register
 * facts, who represents it, its signing rule and its accounts.
 *
 * ## Only under `X-Acting-For`, only with an ACTIVE mandate
 *
 * The company is the one named by the header, and [ActingForResolver] must confirm the token's
 * human holds an active mandate for it — the same fail-closed 403 as every other acting-for route.
 * A request WITHOUT the header, or naming the human themselves, is also a 403: a natural person
 * has no company page, and answering with their personal record would be the one silent fallback
 * this route must not have.
 *
 * ## Only real data
 *
 * Every field comes from a system of record, and a field no source holds is `null` (a list is
 * empty), never a placeholder:
 *  - register facts, seat and status — party-service's entity party;
 *  - representatives — party-service's ACTIVE mandates over the entity (names read from each
 *    agent's party), plus register-listed statutory body members that have no party here yet
 *    (`partyId: null`);
 *  - `signingRule` and `signingRuleAsOf` — the register's last recorded representation text and
 *    original fetch time, via kyb-service's read-only stored extract. This may be older than the
 *    registry refresh TTL; the caller must show the time rather than imply a current legal rule.
 *    Only asked for schemes unambiguously known from the entity's country (`CZ_ICO`, `SK_ICO`);
 *    both are `null` when no extract was ever recorded;
 *  - accounts — account-service, scoped to the entity.
 */
@Path("/customer/v1/business/company")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerBusinessCompanyResource(
    private val upstream: UpstreamClient,
    private val partyMergeResolver: PartyMergeResolver,
    private val actingForResolver: ActingForResolver,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    @ConfigProperty(name = "openbank.edge.account-service-url")
    lateinit var accountServiceUrl: String

    @ConfigProperty(name = "openbank.edge.kyb-service-url", defaultValue = "http://kyb-service.kyb.svc:8157")
    lateinit var kybServiceUrl: String

    @GET
    @Authorize(action = "customer.business.company.read", resource = "")
    @Blocking
    fun company(@HeaderParam(ACTING_FOR) actingFor: String?): Response {
        val human = human()
        val company = companyId(human, actingFor)

        val party = upstream.get("$partyServiceUrl/api/v1/parties/$company", company.toString())
        if (party.status != OK) {
            return Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to "Company not found")).build()
        }
        val node = read(party) ?: return Response.status(Response.Status.BAD_GATEWAY)
            .entity(mapOf("error" to "party-service returned an unreadable company")).build()

        val extract = registerExtract(node, company)
        val representatives = representatives(company, human, extract)
        val accountPage = accountPage(company, null)
            ?: return Response.status(Response.Status.BAD_GATEWAY)
                .entity(mapOf("error" to "account-service returned an unreadable account page")).build()
        val result = linkedMapOf(
            "partyId" to company,
            "legalName" to text(node, "legalName"),
            "registrationNumber" to text(node, "registrationNumber"),
            "legalForm" to text(node, "legalForm"),
            "seat" to (seat(node.path("address")) ?: extract?.let { seat(it.path("registeredAddress")) }),
            "status" to text(node, "status"),
            "representatives" to representatives,
            "signingRule" to extract?.path("representationRule")?.let { text(it, "sourceText") },
            "signingRuleAsOf" to extract?.let { text(it, "fetchedAt") },
            "accounts" to accountPage.accounts,
            "accountsPagination" to accountPage.pagination,
        )
        return Response.ok(result).build()
    }

    @GET
    @Path("/accounts")
    @Authorize(action = "customer.business.company.read", resource = "")
    @Blocking
    fun companyAccounts(@HeaderParam(ACTING_FOR) actingFor: String?, @QueryParam("cursor") cursor: String?): Response {
        val company = companyId(human(), actingFor)
        val page = accountPage(company, cursor)
            ?: return Response.status(Response.Status.BAD_GATEWAY)
                .entity(mapOf("error" to "account-service returned an unreadable account page")).build()
        return Response.ok(mapOf("data" to page.accounts, "pagination" to page.pagination)).build()
    }

    private fun companyId(human: UUID, actingFor: String?): UUID {
        if (actingFor.isNullOrBlank()) throw ForbiddenException("X-Acting-For is required for the company profile")
        val company = actingForResolver.resolve(human, actingFor)
        if (company == human) throw ForbiddenException("X-Acting-For must name a company, not the customer")
        return company
    }

    private fun representatives(company: UUID, human: UUID, extract: JsonNode?): List<Map<String, Any?>> {
        val mandates = read(upstream.get("$partyServiceUrl/api/v1/parties/$company/mandates", company.toString()))
            ?.takeIf { it.isArray }
            ?.filter { text(it, "status") == ACTIVE }
            .orEmpty()
        val fromMandates = mandates.mapNotNull { m ->
            val agent = runCatching { UUID.fromString(text(m, "agentPartyId")) }.getOrNull() ?: return@mapNotNull null
            val name = read(upstream.get("$partyServiceUrl/api/v1/parties/$agent", company.toString()))
                ?.let { text(it, "legalName") }
            linkedMapOf("name" to name, "role" to text(m, "role"), "partyId" to agent, "isYou" to (agent == human))
        }
        val known = fromMandates.mapNotNull { (it["name"] as? String)?.let(::normalise) }.toSet()
        val fromRegister = extract?.path("representatives")?.takeIf { it.isArray }?.mapNotNull { r ->
            val name = text(r, "fullName") ?: return@mapNotNull null
            if (normalise(name) in known) return@mapNotNull null
            linkedMapOf("name" to name, "role" to text(r, "role"), "partyId" to null, "isYou" to false)
        }.orEmpty()
        return fromMandates + fromRegister
    }

    /** The register extract from kyb-service, or null — best-effort, never fatal to the page. */
    private fun registerExtract(party: JsonNode, company: UUID): JsonNode? {
        val number = text(party, "registrationNumber") ?: return null
        val scheme = when (text(party, "registrationCountry")?.uppercase()) {
            "CZ" -> "CZ_ICO"
            "SK" -> "SK_ICO"
            else -> return null
        }
        val encoded = URLEncoder.encode(number, StandardCharsets.UTF_8)
        return try {
            val response = upstream.get(
                "$kybServiceUrl/api/v1/kyb/lookup/cached?scheme=$scheme&identifier=$encoded",
                company.toString(),
            )
            if (response.status == OK) read(response) else null
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.warnf(e, "register extract for %s unavailable; signingRule omitted", company)
            null
        }
    }

    private data class AccountPage(val accounts: List<Map<String, String?>>, val pagination: JsonNode)

    private fun accountPage(company: UUID, cursor: String?): AccountPage? {
        val suffix = cursor?.takeIf { it.isNotBlank() }
            ?.let { "&cursor=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }.orEmpty()
        val node = read(upstream.get("$accountServiceUrl/api/v1/accounts?partyId=$company$suffix", company.toString()))
            ?: return null
        val rows = node.path("data").takeIf { it.isArray } ?: return null
        val pagination = node.path("pagination").takeIf { it.isObject } ?: return null
        val accounts = rows.filter { it.isObject }.map { a ->
            linkedMapOf(
                "id" to text(a, "id"),
                "iban" to text(a, "accountNumber"),
                "currency" to text(a, "currencyCode"),
                "product" to text(a, "accountType"),
            )
        }
        return AccountPage(accounts, pagination)
    }

    private fun seat(address: JsonNode?): Map<String, String?>? {
        if (address == null || !address.isObject) return null
        val street = listOfNotNull(text(address, "line1"), text(address, "line2")).joinToString(", ").ifBlank { null }
        val seat = linkedMapOf(
            "street" to street,
            "city" to text(address, "city"),
            "postalCode" to text(address, "postalCode"),
            "country" to text(address, "countryCode"),
        )
        return seat.takeIf { it.values.any { v -> v != null } }
    }

    private fun read(response: Response): JsonNode? {
        if (response.status != OK) return null
        val body = response.entity as? String ?: return null
        return runCatching { objectMapper.readTree(body) }.getOrNull()
    }

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }

    private fun human(): UUID {
        val claim = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
            ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val claimed = runCatching { UUID.fromString(claim) }
            .getOrElse { throw ForbiddenException("party_id claim is not a UUID") }
        return partyMergeResolver.resolve(claimed)
    }

    private companion object {
        const val ACTING_FOR = "X-Acting-For"
        const val OK = 200
        const val ACTIVE = "ACTIVE"
    }
}

private fun normalise(name: String) = name.trim().lowercase().replace(Regex("\\s+"), " ")
