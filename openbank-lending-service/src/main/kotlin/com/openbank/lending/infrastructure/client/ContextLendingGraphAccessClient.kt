// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.LendingAssignedCandidatesResult
import com.openbank.lending.application.port.out.LendingGraphAccessDecision
import com.openbank.lending.application.port.out.LendingGraphAccessPort
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

private const val ACCESS_TIMEOUT_MILLIS = 3_000L

/** Carries the investigator's own bearer; a service token would change the access decision. */
@RegisterRestClient(configKey = "context-lending-access")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/context/lending-loans")
interface ContextLendingGraphAccessClient {
    @GET
    @Path("/{loanId}/access")
    @Timeout(ACCESS_TIMEOUT_MILLIS)
    fun check(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("Authorization") bearer: String,
        @HeaderParam("X-Investigation-Case-Id") caseId: String,
        @HeaderParam("X-Investigation-Purpose") purpose: String,
    ): Uni<Response>

    @GET
    @Path("/{loanId}/assigned-candidates")
    @Timeout(ACCESS_TIMEOUT_MILLIS)
    fun assignedCandidates(
        @PathParam("loanId") loanId: UUID,
        @HeaderParam("Authorization") bearer: String,
        @HeaderParam("X-Investigation-Case-Id") caseId: String,
        @HeaderParam("X-Investigation-Purpose") purpose: String,
    ): Uni<Response>
}

data class ContextAssignedCandidates(val ids: List<UUID>, val truncated: Boolean)

@ApplicationScoped
class RestLendingGraphAccessAdapter(@param:RestClient private val context: ContextLendingGraphAccessClient) :
    LendingGraphAccessPort {
    override suspend fun check(loanId: UUID, bearer: String): LendingGraphAccessDecision = try {
        context.check(loanId, bearer, loanId.toString(), PURPOSE).awaitSuspending().use { response ->
            if (response.status == Response.Status.NO_CONTENT.statusCode) {
                LendingGraphAccessDecision.ALLOWED
            } else {
                decisionFor(response.status)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: WebApplicationException) {
        decisionFor(e.response?.status)
    } catch (_: Exception) {
        LendingGraphAccessDecision.UNAVAILABLE
    }

    override suspend fun assignedCandidates(rootLoanId: UUID, bearer: String): LendingAssignedCandidatesResult = try {
        context.assignedCandidates(
            rootLoanId,
            bearer,
            rootLoanId.toString(),
            PURPOSE,
        ).awaitSuspending().use { candidateResponse(it, rootLoanId) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: WebApplicationException) {
        when (decisionFor(e.response?.status)) {
            LendingGraphAccessDecision.DENIED -> LendingAssignedCandidatesResult.Denied
            else -> LendingAssignedCandidatesResult.Unavailable
        }
    } catch (_: Exception) {
        LendingAssignedCandidatesResult.Unavailable
    }

    private fun candidateResponse(response: Response, rootLoanId: UUID): LendingAssignedCandidatesResult =
        when (response.status) {
            Response.Status.OK.statusCode -> validCandidates(response, rootLoanId)
            Response.Status.UNAUTHORIZED.statusCode,
            Response.Status.FORBIDDEN.statusCode,
            Response.Status.NOT_FOUND.statusCode,
            -> LendingAssignedCandidatesResult.Denied
            else -> LendingAssignedCandidatesResult.Unavailable
        }

    private fun validCandidates(response: Response, rootLoanId: UUID): LendingAssignedCandidatesResult {
        val body = response.readEntity(ContextAssignedCandidates::class.java)
        if (body.ids.size > MAX_CANDIDATES || body.ids.size != body.ids.toSet().size || rootLoanId in body.ids) {
            return LendingAssignedCandidatesResult.Unavailable
        }
        return LendingAssignedCandidatesResult.Available(body.ids, body.truncated)
    }

    private fun decisionFor(status: Int?): LendingGraphAccessDecision = when (status) {
        Response.Status.UNAUTHORIZED.statusCode,
        Response.Status.FORBIDDEN.statusCode,
        Response.Status.NOT_FOUND.statusCode,
        -> LendingGraphAccessDecision.DENIED
        else -> LendingGraphAccessDecision.UNAVAILABLE
    }

    companion object {
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
        private const val MAX_CANDIDATES = 256
    }
}
