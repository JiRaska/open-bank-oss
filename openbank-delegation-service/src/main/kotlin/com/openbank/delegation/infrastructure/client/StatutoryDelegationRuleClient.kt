// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.port.out.StatutoryDelegationRuleReader
import com.openbank.delegation.application.usecase.DelegationGrantorAuthorityUnavailableException
import com.openbank.delegation.domain.model.StatutoryDelegationRule
import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.audit.decision.InputDigest
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class StatutoryRepresentativeResponse(
    val partyId: UUID,
    val officeSlots: Set<Int>,
    val mandateEvidenceHash: String,
)

data class StatutoryEvidenceResponse(
    val caseId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val attestedAt: Instant,
    val mode: String,
    val requiredSignatures: Int,
    val requiredOffices: List<String>,
    val representatives: List<StatutoryRepresentativeResponse>,
)

data class StatutoryRuleResponse(
    val principalPartyId: UUID,
    val evidence: StatutoryEvidenceResponse,
    val mandatesByRepresentative: Map<UUID, UUID>,
    val effectiveFrom: Instant,
    val validTo: Instant?,
)

@OidcClientFilter("m2m")
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/parties")
interface PartyStatutoryRuleRestClient {
    @GET
    @Path("/{id}/statutory-representation")
    suspend fun latest(@PathParam("id") principalPartyId: UUID): StatutoryRuleResponse
}

@OidcClientFilter("m2m")
@RegisterRestClient(configKey = "kyb-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/kyb/cases")
interface KybStatutoryRuleRestClient {
    @GET
    @Path("/{id}/statutory-representation/{attestationId}/current")
    suspend fun verify(
        @PathParam("id") caseId: UUID,
        @PathParam("attestationId") attestationId: UUID,
        @QueryParam("entityPartyId") entityPartyId: UUID,
    ): Response
}

@ApplicationScoped
class RestStatutoryDelegationRuleReader(
    @RestClient private val party: PartyStatutoryRuleRestClient,
    @RestClient private val kyb: KybStatutoryRuleRestClient,
    private val clock: Clock,
) : StatutoryDelegationRuleReader {

    @Suppress("TooGenericExceptionCaught") // Every unknown source failure refuses a new or pending operation.
    override suspend fun current(principalPartyId: UUID): StatutoryDelegationRule? {
        try {
            val snapshot = party.latest(principalPartyId)
            val rule = snapshot.toRule(principalPartyId, clock.instant()) ?: return null
            return kyb.verify(rule.caseId, rule.attestationId, principalPartyId).use { response ->
                when (response.status) {
                    Response.Status.NO_CONTENT.statusCode -> rule
                    Response.Status.NOT_FOUND.statusCode -> null
                    else -> throw authorityUnavailable()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebApplicationException) {
            if (e.response?.status == Response.Status.NOT_FOUND.statusCode) return null
            throw authorityUnavailable(e)
        } catch (e: Exception) {
            throw authorityUnavailable(e)
        }
    }

    private fun StatutoryRuleResponse.toRule(expected: UUID, at: Instant): StatutoryDelegationRule? {
        if (principalPartyId != expected || effectiveFrom.isAfter(at) || validTo?.isAfter(at) == false) return null
        if (evidence.mode !in JOINT_MODES || evidence.attestedAt.isAfter(effectiveFrom)) return null
        val members = evidence.representatives
        if (members.size !in 2..MAX_MEMBERS || evidence.requiredSignatures !in 2..members.size) return null
        if (evidence.mode == "JOINT_ALL" && evidence.requiredSignatures != members.size) return null
        if (members.map { it.partyId }.toSet() != mandatesByRepresentative.keys) return null
        if (mandatesByRepresentative.size != members.size) return null
        val digest = InputDigest.sha256(
            mapOf(
                "ruleTextHash" to InputDigest(evidence.ruleTextHash).hex,
                "attestedAt" to evidence.attestedAt.toString(),
                "effectiveFrom" to effectiveFrom.toString(),
                "validTo" to validTo?.toString().orEmpty(),
                "mode" to evidence.mode,
                "requiredSignatures" to evidence.requiredSignatures.toString(),
                "requiredOffices" to CanonicalJson.write(evidence.requiredOffices),
                "members" to CanonicalJson.write(
                    members.sortedBy { it.partyId }.map {
                        mapOf(
                            "partyId" to it.partyId,
                            "officeSlots" to it.officeSlots.sorted(),
                            "mandateEvidenceHash" to InputDigest(it.mandateEvidenceHash).hex,
                        )
                    },
                ),
            ),
        )
        return StatutoryDelegationRule(
            principalPartyId,
            evidence.caseId,
            evidence.attestationId,
            digest,
            mandatesByRepresentative.toMap(),
            validTo,
            evidence.requiredSignatures,
            evidence.requiredOffices.size,
            members.associate { it.partyId to it.officeSlots },
        )
    }

    private fun authorityUnavailable(cause: Throwable? = null) = DelegationGrantorAuthorityUnavailableException(
        "current statutory signing authority could not be established",
        cause,
    )

    private companion object {
        const val MAX_MEMBERS = 64
        val JOINT_MODES = setOf("JOINT_N", "JOINT_ALL")
    }
}
