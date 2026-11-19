// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.port.out.StatutoryDelegationSca
import com.openbank.delegation.application.usecase.DelegationScaException
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class StatutoryScaRequest(
    val partyId: UUID,
    val companyPartyId: UUID,
    val approvalRequestId: String,
    val payloadSha256: String,
)

data class StatutoryScaReceipt(
    val id: UUID,
    val partyId: UUID,
    val onBehalfOfPartyId: UUID?,
    val decidedByPartyId: UUID?,
    val purpose: String,
    val status: String,
    val consumedAt: OffsetDateTime?,
)

@OidcClientFilter("m2m")
@RegisterRestClient(configKey = "sca-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/sca/challenges")
interface StatutoryScaRestClient {
    @POST
    @Path("/{id}/consume-statutory-approval")
    suspend fun consume(
        @PathParam("id") challengeId: UUID,
        @HeaderParam("Idempotency-Key") requestKey: String,
        request: StatutoryScaRequest,
    ): StatutoryScaReceipt
}

@ApplicationScoped
class RestStatutoryDelegationSca(@RestClient private val client: StatutoryScaRestClient) : StatutoryDelegationSca {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun consume(
        operation: StatutoryDelegationOperation,
        actorPartyId: UUID,
        challengeId: UUID,
    ): Instant {
        val company = operation.rule.principalPartyId
        val receipt = try {
            client.consume(
                challengeId,
                challengeId.toString(),
                StatutoryScaRequest(actorPartyId, company, operation.approvalRequestId, operation.payloadDigest.hex),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DelegationScaException("statutory approval receipt could not be established", e)
        }
        val correctActor = receipt.partyId == actorPartyId && receipt.decidedByPartyId == actorPartyId
        val correctChallenge = receipt.id == challengeId && receipt.purpose == "APPROVAL"
        if (!correctActor || !correctChallenge || receipt.onBehalfOfPartyId != company) {
            throw DelegationScaException("statutory approval receipt does not match the operation")
        }
        val consumedAt = receipt.consumedAt
        if (receipt.status != "COMPLETED" || consumedAt == null) {
            throw DelegationScaException("statutory approval receipt does not record consumption")
        }
        return consumedAt.toInstant()
    }
}
