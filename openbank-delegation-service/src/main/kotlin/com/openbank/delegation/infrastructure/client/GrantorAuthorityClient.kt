// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.delegation.application.port.out.GrantorAuthority
import com.openbank.delegation.application.port.out.GrantorAuthorityClient
import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.delegation.application.port.out.StatutoryRuleClient
import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.delegation.domain.model.StatutoryRepresentative
import com.openbank.delegation.domain.model.StatutoryRuleMode
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.logging.Log
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Clock
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthorityPartyResponse(
    val id: UUID,
    val partyType: String,
    val status: String,
    val legalName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActingForMandateResponse(val authority: String? = null, val requiredSignatures: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActingForResponse(val partyId: UUID, val mandate: ActingForMandateResponse? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatutoryRepresentativeResponse(
    val partyId: UUID,
    val registryRepresentativeIndices: Set<Int>,
    val officeTags: Set<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepresentationPolicyResponse(
    val id: UUID,
    val principalPartyId: UUID,
    val revision: Long,
    val sourceCaseId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val mode: String,
    val requiredSignatures: Int,
    val requiredOffices: List<String>,
    val registryRepresentativeCount: Int,
    val eligibleRepresentatives: List<StatutoryRepresentativeResponse>,
    val effectiveFrom: Instant,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatutoryMandateResponse(
    val principalPartyId: UUID,
    val agentPartyId: UUID,
    val role: String,
    val authority: String,
    val requiredSignatures: Int?,
    val status: String,
    val evidenceRef: String?,
    val validFrom: Instant,
    val validTo: Instant?,
)

@Path("/api/v1/parties")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PartyAuthorityRestClient {
    @GET
    @Path("/{id}")
    suspend fun getParty(@PathParam("id") id: UUID): AuthorityPartyResponse

    @GET
    @Path("/{id}/acting-for")
    suspend fun actingFor(@PathParam("id") actorPartyId: UUID): List<ActingForResponse>

    @GET
    @Path("/{id}/representation-policy")
    suspend fun representationPolicy(@PathParam("id") principalPartyId: UUID): RepresentationPolicyResponse

    @GET
    @Path("/{id}/mandates")
    suspend fun mandates(@PathParam("id") principalPartyId: UUID): List<StatutoryMandateResponse>
}

/**
 * Resolves the principal type and re-checks an organisation mandate at the authority boundary.
 * `acting-for` returns active, in-window mandates over live entities. A profile match is not
 * sufficient for JOINT representation: one signer cannot issue a delegation alone. Until the
 * durable co-signing workflow exists, only an explicit SOLE/1 mandate is independently usable.
 * Every transport or parse failure remains distinguishable from a real denial.
 */
@ApplicationScoped
class RestGrantorAuthorityClient @Inject constructor(@RestClient private val client: PartyAuthorityRestClient) :
    GrantorAuthorityClient {

    // Every transport or parse failure must become an explicit fail-closed verdict.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun authorityFor(principalPartyId: UUID, actorPartyId: UUID): GrantorAuthority = try {
        val principal = client.getParty(principalPartyId)
        val authorized = when (principal.partyType) {
            "INDIVIDUAL" -> actorPartyId == principalPartyId && principal.status == "ACTIVE"
            "SOLE_TRADER", "COMPANY", "TRUST" ->
                principal.status == "ACTIVE" &&
                    client.actingFor(actorPartyId).any {
                        it.partyId == principalPartyId &&
                            it.mandate?.authority == "SOLE" &&
                            it.mandate.requiredSignatures == 1
                    }
            else -> false
        }
        GrantorAuthority(
            verdict = if (authorized) GrantorAuthorityVerdict.AUTHORIZED else GrantorAuthorityVerdict.DENIED,
            displayName = principal.legalName?.trim()?.takeIf { it.isNotEmpty() },
            partyType = principal.partyType,
        )
    } catch (e: NotFoundException) {
        GrantorAuthority(GrantorAuthorityVerdict.DENIED)
    } catch (e: Exception) {
        Log.errorf(e, "grantor authority lookup for principal %s failed — refusing", principalPartyId)
        GrantorAuthority(GrantorAuthorityVerdict.UNVERIFIABLE)
    }
}

/** Fail-closed JOINT rule resolver; never derives a quorum from one acting-for mandate. */
@ApplicationScoped
class RestStatutoryRuleClient(
    @RestClient private val client: PartyAuthorityRestClient,
    @RestClient private val kyb: KybAttestationRestClient,
    private val clock: Clock,
) : StatutoryRuleClient {
    @Inject
    constructor(
        @RestClient client: PartyAuthorityRestClient,
        @RestClient kyb: KybAttestationRestClient,
    ) : this(client, kyb, Clock.systemUTC())

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun resolve(principalPartyId: UUID, actorPartyId: UUID): StatutoryRuleResolution = try {
        val principal = client.getParty(principalPartyId)
        if (principal.id != principalPartyId || principal.status != "ACTIVE" || principal.partyType != "COMPANY") {
            StatutoryRuleResolution.Denied
        } else {
            val policy = client.representationPolicy(principalPartyId)
            val mandates = client.mandates(principalPartyId)
            val rule = policy.toRule()
            val now = clock.instant()
            val policyIsEffective = policy.principalPartyId == principalPartyId && !now.isBefore(policy.effectiveFrom)
            val actorIsInRoster = rule.eligibleRepresentatives.any { it.partyId == actorPartyId }
            val attestationIsCurrent = if (policyIsEffective &&
                actorIsInRoster &&
                rule.hasCurrentRoster(mandates, now)
            ) {
                kyb.current(rule.attestationId).matches(rule)
            } else {
                false
            }
            if (attestationIsCurrent) {
                StatutoryRuleResolution.RosterMatched(rule)
            } else {
                StatutoryRuleResolution.Denied
            }
        }
    } catch (e: NotFoundException) {
        StatutoryRuleResolution.Denied
    } catch (e: Exception) {
        Log.errorf(e, "statutory rule lookup for principal %s failed — refusing", principalPartyId)
        StatutoryRuleResolution.Unverifiable
    }

    private fun KybAttestationStatusResponse.matches(rule: StatutoryRepresentationRule): Boolean =
        id == rule.attestationId &&
            current &&
            ruleTextHash == rule.ruleTextHash &&
            confirmedSigners == rule.requiredSignatures &&
            confirmedRoles.sorted() == rule.requiredOffices.sorted()

    private fun StatutoryRepresentationRule.hasCurrentRoster(
        mandates: List<StatutoryMandateResponse>,
        now: Instant,
    ): Boolean = eligibleRepresentatives.all { representative ->
        mandates.any { it.isCurrentFor(this, representative.partyId, now) }
    }

    private fun StatutoryMandateResponse.isCurrentFor(
        rule: StatutoryRepresentationRule,
        representativePartyId: UUID,
        now: Instant,
    ): Boolean {
        val identityMatches = principalPartyId == rule.principalPartyId && agentPartyId == representativePartyId
        val authorityMatches = role == "LEGAL_REPRESENTATIVE" &&
            authority == "JOINT" &&
            requiredSignatures == rule.requiredSignatures
        val sourceMatches = evidenceRef?.startsWith("kyb-case:${rule.sourceCaseId}:signer:") == true
        val timeMatches = !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo))
        return identityMatches && authorityMatches && sourceMatches && status == "ACTIVE" && timeMatches
    }

    private fun RepresentationPolicyResponse.toRule(): StatutoryRepresentationRule = StatutoryRepresentationRule(
        policyId = id,
        principalPartyId = principalPartyId,
        revision = revision,
        sourceCaseId = sourceCaseId,
        attestationId = attestationId,
        ruleTextHash = ruleTextHash,
        mode = StatutoryRuleMode.valueOf(mode),
        requiredSignatures = requiredSignatures,
        requiredOffices = requiredOffices,
        registryRepresentativeCount = registryRepresentativeCount,
        eligibleRepresentatives = eligibleRepresentatives.map {
            StatutoryRepresentative(it.partyId, it.registryRepresentativeIndices, it.officeTags)
        },
    )
}
