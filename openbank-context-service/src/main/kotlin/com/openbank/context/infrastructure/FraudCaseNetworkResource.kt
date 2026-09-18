// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextQueryService
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Clock
import java.util.UUID

data class FraudSharedReference(val type: String, val sourceId: UUID)

data class FraudRelatedCase(val evidence: FraudCaseSourceSnapshot, val shared: List<FraudSharedReference>)

/** A bounded, evidence-only one-hop view over currently assigned source-open Fraud cases. */
data class FraudCaseNetwork(
    val root: FraudCaseSourceSnapshot,
    val related: List<FraudRelatedCase>,
    val inspectedCandidates: Int,
    val candidateTruncated: Boolean,
)

@Path("/api/v1/context/fraud-cases")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_ADMIN")
class FraudCaseNetworkResource(
    private val queries: ContextQueryService,
    private val references: FraudCaseReferenceRepository,
    private val source: FraudCaseSourceEvidence,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/{caseId}/network")
    suspend fun network(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("X-Investigation-Case-Id") investigationCaseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(investigationCaseId == caseId.toString()) { "caseId must identify the assigned Fraud case" }
        require(purpose == PURPOSE) { "FRAUD_INVESTIGATION is required" }
        val bearer = (identity.principal as? JsonWebToken)?.rawToken?.let { "Bearer $it" }
            ?: return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        val actor = Investigator(identity.principal.name, identity.roles.sorted())
        val at = clock.instant()
        return try {
            queries.fraudCaseEvidence(
                caseId.toString(),
                actor,
                InvestigationContext(investigationCaseId, purpose, at),
                summarize = ContextDisclosureSummaries::response,
            ) {
                val root = source.read(caseId, bearer)
                val candidates = references.assignedCandidates(caseId, actor.id, at)
                val related = coroutineScope {
                    candidates.ids.map { candidate ->
                        async { relatedCase(candidate, actor, bearer, at, root) }
                    }.awaitAll().filterNotNull()
                }
                Response.ok(FraudCaseNetwork(root, related, candidates.ids.size, candidates.truncated))
                    .header("Cache-Control", "no-store").build()
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: FraudCaseSourceDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        } catch (_: FraudCaseSourceUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        } catch (_: FraudReferenceUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }

    private suspend fun relatedCase(
        id: UUID,
        actor: Investigator,
        bearer: String,
        at: java.time.Instant,
        root: FraudCaseSourceSnapshot,
    ): FraudRelatedCase? = try {
        queries.fraudCaseEvidence(
            id.toString(),
            actor,
            InvestigationContext(id.toString(), PURPOSE, at),
            summarize = ContextDisclosureSummaries::fraud,
        ) {
            val evidence = source.read(id, bearer)
            sharedFraudReferences(root, evidence).takeIf(List<*>::isNotEmpty)?.let { FraudRelatedCase(evidence, it) }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: ContextAccessDenied) {
        null
    } catch (_: FraudCaseSourceDenied) {
        null
    }

    private companion object {
        const val PURPOSE = "FRAUD_INVESTIGATION"
    }
}

/** Same-role equality is a lead. Cross-role UUID equality is not treated as identity. */
internal fun sharedFraudReferences(
    root: FraudCaseSourceSnapshot,
    related: FraudCaseSourceSnapshot,
): List<FraudSharedReference> = buildList {
    if (root.accountId == related.accountId) {
        root.accountId?.let { add(FraudSharedReference("ACCOUNT", it)) }
    }
    if (root.counterpartyId == related.counterpartyId) {
        root.counterpartyId?.let { add(FraudSharedReference("COUNTERPARTY", it)) }
    }
}
