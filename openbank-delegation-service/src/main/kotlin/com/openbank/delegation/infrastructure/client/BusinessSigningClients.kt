// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.delegation.application.port.out.ActingForEntity
import com.openbank.delegation.application.port.out.ApprovalLink
import com.openbank.delegation.application.port.out.ApprovalScaVerifier
import com.openbank.delegation.application.port.out.MandateDirectory
import com.openbank.delegation.application.port.out.MandateDirectoryUnavailableException
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ScaVerdict
import com.openbank.delegation.domain.model.MandateAuthority
import com.openbank.delegation.domain.model.RepresentationMandate
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.logging.Log
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Clock
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class MandateResponse(
    val agentPartyId: UUID? = null,
    val authority: String? = null,
    val requiredSignatures: Int? = null,
    val status: String? = null,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SigningPartyResponse(
    val id: UUID? = null,
    val status: String? = null,
    val legalName: String? = null,
    val tradingName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SigningActingForResponse(
    val partyId: UUID? = null,
    val legalName: String? = null,
    val tradingName: String? = null,
)

/** party-service's mandate register (ADR-0284 D3), read live for every signing decision. */
@Path("/api/v1/parties")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PartyMandateRestClient {
    @GET
    @Path("/{id}")
    suspend fun getParty(@PathParam("id") id: UUID): SigningPartyResponse

    @GET
    @Path("/{id}/mandates")
    suspend fun mandates(@PathParam("id") entityPartyId: UUID): List<MandateResponse>

    @GET
    @Path("/{id}/acting-for")
    suspend fun actingFor(@PathParam("id") humanPartyId: UUID): List<SigningActingForResponse>
}

/**
 * No retry and no cache on purpose: this answers "may this person sign for the company right
 * now", and a cached or stale answer is exactly the stale-mandate threat (ADR-0312). Any failure
 * is thrown as [MandateDirectoryUnavailableException] — never an empty list.
 */
@ApplicationScoped
class RestMandateDirectory(
    @RestClient private val client: PartyMandateRestClient,
    private val people: PartyEligibilityClient,
    private val clock: Clock,
) : MandateDirectory {

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun entityName(entityPartyId: UUID): String? = try {
        client.getParty(entityPartyId).let { p ->
            p.tradingName?.takeIf { it.isNotBlank() }
                ?: p.legalName?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    /** The natural person's name via pid-service — the same source grant offers snapshot names from. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun personName(partyId: UUID): String? = try {
        people.eligibilityOf(partyId).displayName?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun activeMandates(entityPartyId: UUID): List<RepresentationMandate> = try {
        val entity = client.getParty(entityPartyId)
        if (entity.status != "ACTIVE") {
            emptyList()
        } else {
            val now = clock.instant()
            client.mandates(entityPartyId)
                .filter { it.status == "ACTIVE" && it.agentPartyId != null }
                .filter {
                    (it.validFrom == null || !it.validFrom.isAfter(now)) &&
                        (it.validTo == null || it.validTo.isAfter(now))
                }
                .mapNotNull { m ->
                    val authority =
                        MandateAuthority.entries.firstOrNull { it.name == m.authority } ?: return@mapNotNull null
                    RepresentationMandate(m.agentPartyId!!, authority, m.requiredSignatures ?: 1)
                }
                .distinctBy { it.agentPartyId }
        }
    } catch (e: WebApplicationException) {
        if (e.response?.status == HTTP_NOT_FOUND) {
            emptyList()
        } else {
            throw MandateDirectoryUnavailableException("mandate register read failed: HTTP ${e.response?.status}", e)
        }
    } catch (e: Exception) {
        Log.errorf(e, "mandate register read for %s failed — refusing", entityPartyId)
        throw MandateDirectoryUnavailableException("mandate register could not be read", e)
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun actingFor(humanPartyId: UUID): List<ActingForEntity> = try {
        client.actingFor(humanPartyId).mapNotNull { r ->
            r.partyId?.let { ActingForEntity(it, r.tradingName?.takeIf { n -> n.isNotBlank() } ?: r.legalName) }
        }
    } catch (e: Exception) {
        Log.errorf(e, "acting-for read for %s failed — refusing", humanPartyId)
        throw MandateDirectoryUnavailableException("acting-for could not be read", e)
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApprovalScaChallengeResponse(
    val id: UUID? = null,
    val partyId: UUID? = null,
    val purpose: String? = null,
    val status: String? = null,
    val consumedAt: String? = null,
)

/**
 * sca-service's consume with the ADR-0312 linking data. `approvalRequestId` + `payloadSha256` are
 * the approval link; `amount`/`currency`/`creditor` additionally link a PAYMENT. sca-service
 * refuses a challenge whose stored linking data differs.
 */
data class ConsumeApprovalChallengeRequest(
    val partyId: UUID,
    val approvalRequestId: UUID,
    val payloadSha256: String,
    val amount: String? = null,
    val currency: String? = null,
    val creditor: String? = null,
)

@Path("/api/v1/sca/challenges")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "sca-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface ApprovalScaRestClient {
    @GET
    @Path("/{id}")
    suspend fun getChallenge(@PathParam("id") id: UUID): ApprovalScaChallengeResponse

    @POST
    @Path("/{id}/consume")
    suspend fun consume(
        @PathParam("id") id: UUID,
        request: ConsumeApprovalChallengeRequest,
    ): ApprovalScaChallengeResponse
}

/** No retry on consume: it is a single-use state change, and a retried success reads as a 409. */
@ApplicationScoped
class RestApprovalScaVerifier(@RestClient private val client: ApprovalScaRestClient) : ApprovalScaVerifier {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun verifyConsumedInitiatorChallenge(challengeId: UUID, partyId: UUID): ScaVerdict = try {
        val c = client.getChallenge(challengeId)
        val ok =
            c.partyId == partyId &&
                c.purpose == PAYMENT_PURPOSE &&
                c.status == COMPLETED &&
                !c.consumedAt.isNullOrBlank()
        if (ok) ScaVerdict.VERIFIED else ScaVerdict.REFUSED
    } catch (e: WebApplicationException) {
        verdictFor(e)
    } catch (e: Exception) {
        Log.errorf(e, "initiator SCA read for challenge %s failed", challengeId)
        ScaVerdict.UNAVAILABLE
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun consumeApprovalChallenge(challengeId: UUID, partyId: UUID, link: ApprovalLink): ScaVerdict =
        try {
            val c = client.consume(
                challengeId,
                ConsumeApprovalChallengeRequest(
                    partyId = partyId,
                    approvalRequestId = link.approvalRequestId,
                    payloadSha256 = link.payloadSha256,
                    amount = link.amount?.amount?.toPlainString(),
                    currency = link.amount?.currency,
                    creditor = link.creditorIban,
                ),
            )
            if (c.partyId == partyId && c.purpose == APPROVAL_PURPOSE) ScaVerdict.VERIFIED else ScaVerdict.REFUSED
        } catch (e: WebApplicationException) {
            verdictFor(e)
        } catch (e: Exception) {
            Log.errorf(e, "approval SCA consume for challenge %s failed", challengeId)
            ScaVerdict.UNAVAILABLE
        }

    private fun verdictFor(e: WebApplicationException): ScaVerdict {
        val status = e.response?.status ?: 0
        return if (status in CLIENT_ERRORS) ScaVerdict.REFUSED else ScaVerdict.UNAVAILABLE
    }

    private companion object {
        const val PAYMENT_PURPOSE = "PAYMENT_INITIATION"
        const val APPROVAL_PURPOSE = "APPROVAL"
        const val COMPLETED = "COMPLETED"
        val CLIENT_ERRORS = 400..499
    }
}
