// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.rest

import com.openbank.flakytest.application.port.incoming.AnalyzeTestIntelligenceUseCase
import com.openbank.flakytest.application.port.incoming.GetFindingsUseCase
import com.openbank.flakytest.application.port.incoming.RunFlakyTestCheckUseCase
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.FlakyTestReport
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.flakytest.domain.model.TestIntelligenceAnalysisRequest
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking

@Path("/api/v1/flaky-test-hunter")
@Produces(MediaType.APPLICATION_JSON)
class FlakyTestResource(
    private val runCheck: RunFlakyTestCheckUseCase,
    private val getFindings: GetFindingsUseCase,
    private val testIntelligenceAnalysis: AnalyzeTestIntelligenceUseCase,
) {
    @POST
    @Path("/check/trigger")
    @RolesAllowed("ROLE_ADMIN")
    fun triggerCheck(): FlakyTestReport = runBlocking {
        runCheck.run(RunTrigger.OPERATOR_MANUAL)
    }

    /**
     * Starts the durable operator workflow without holding an Admin UI request open for a fleet
     * scan and any per-finding diagnosis. The id is the operator-visible handle; completion and
     * any proposal remain recorded by the workflow, not implied by this accepted response.
     */
    @POST
    @Path("/check/trigger-async")
    @RolesAllowed("ROLE_ADMIN")
    fun triggerCheckAsync(): Response = runBlocking {
        Response.accepted(FlakyTestCheckStarted(runCheck.startDetached(RunTrigger.OPERATOR_MANUAL))).build()
    }

    /**
     * Idempotent recovery admission on a distinct route. An old backend has no matching route and
     * therefore returns 404 before reaching Temporal instead of silently ignoring the key.
     */
    @POST
    @Path("/check/trigger-async-idempotent")
    @RolesAllowed("ROLE_ADMIN")
    fun triggerCheckAsyncIdempotent(@HeaderParam("Idempotency-Key") idempotencyKey: String?): Response = runBlocking {
        val boundedKey = requireNotNull(idempotencyKey) { "Idempotency-Key header is required" }
        Response.accepted(
            FlakyTestCheckStarted(runCheck.startDetached(RunTrigger.OPERATOR_MANUAL, boundedKey)),
        ).build()
    }

    /** The agent receives only a bounded provenance projection and cannot apply a remediation. */
    @POST
    @Path("/evidence/analyze")
    @RolesAllowed("ROLE_ADMIN")
    suspend fun analyzeEvidence(request: TestIntelligenceAnalysisRequest): List<FlakyTestFinding> =
        testIntelligenceAnalysis.analyze(request)

    @GET
    @Path("/findings")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getActiveFindings(): List<FlakyTestFinding> = getFindings.getActive()

    @GET
    @Path("/findings/{id}")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getFinding(@PathParam("id") id: String): FlakyTestFinding =
        getFindings.getById(id) ?: throw NotFoundException("Finding $id not found")
}

data class FlakyTestCheckStarted(val workflowId: String)
