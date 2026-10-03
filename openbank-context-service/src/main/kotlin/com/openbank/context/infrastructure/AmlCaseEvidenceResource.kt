// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextDisclosure
import com.openbank.context.application.ContextQueryService
import com.openbank.context.application.ContextReadResult
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

@Path("/api/v1/context/aml-cases")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_COMPLIANCE", "ROLE_ADMIN")
class AmlCaseEvidenceResource(
    private val queries: ContextQueryService,
    private val history: AmlCaseHistoryRepository,
    private val source: AmlCaseSourceStatus,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/{id}/network")
    suspend fun assignedNetwork(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("effectiveAt") effectiveAt: String?,
        @QueryParam("knownAt") knownAt: String?,
    ): Response {
        val now = clock.instant()
        val effective = timestamp(effectiveAt, now)
        val known = knownAt?.let { timestamp(it, now) }
        require(effective <= now && (known == null || known <= now)) { "AML evidence cannot establish future activity" }
        require(caseId == id.toString()) { "caseId must identify the assigned AML case" }
        require(purpose == PURPOSE) { "AML_INVESTIGATION is required" }
        val actor = Investigator(identity.principal.name, identity.roles.sorted())
        return try {
            queries.amlCaseEvidence(id.toString(), actor, InvestigationContext(caseId, purpose, effective, known)) {
                if (!source.isOpen(id)) throw ContextAccessDenied()
                val cutoff = known ?: history.databaseNow()
                val root = history.history(id, effective, cutoff)
                val candidates = history.assignedRelatedCases(root, actor.id, now)
                val related = coroutineScope {
                    candidates.map { candidate ->
                        async {
                            try {
                                queries.amlCaseEvidence(
                                    candidate.toString(),
                                    actor,
                                    InvestigationContext(candidate.toString(), purpose, effective, known),
                                ) {
                                    ContextReadResult(
                                        if (source.isOpen(candidate)) {
                                            history.history(candidate, effective, cutoff, RELATED_OBSERVATION_LIMIT)
                                                .takeIf { related -> sharedReferences(root, related) }
                                        } else {
                                            null
                                        },
                                        null,
                                    )
                                }
                            } catch (_: ContextAccessDenied) {
                                // Revocation between candidate discovery and authorization is expected.
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                val visible = listOf(root) + related
                ContextReadResult(
                    Response.ok(AmlCaseNetwork(root, related)).header("Cache-Control", "no-store").build(),
                    ContextDisclosure(
                        visible.flatMap { selected -> selected.observations.map { it.evidenceRef } },
                        visible.sumOf { it.observations.size },
                        visible.any { it.truncated },
                    ),
                )
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        } catch (_: AmlCaseSourceUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }

    @GET
    @Path("/{id}")
    suspend fun caseHistory(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("effectiveAt") effectiveAt: String?,
        @QueryParam("knownAt") knownAt: String?,
    ): Response {
        val now = clock.instant()
        val effective = timestamp(effectiveAt, now)
        val known = knownAt?.let { timestamp(it, now) }
        require(effective <= now && (known == null || known <= now)) { "AML evidence cannot establish future activity" }
        require(caseId == id.toString()) { "caseId must identify the assigned AML case" }
        require(purpose == PURPOSE) { "AML_INVESTIGATION is required" }
        return try {
            queries.amlCaseEvidence(
                id.toString(),
                Investigator(identity.principal.name, identity.roles.sorted()),
                InvestigationContext(caseId, purpose, effective, known),
            ) {
                // The owning service is authoritative for the current case state. A delayed
                // Kafka close must never leave historical evidence readable as an open case.
                if (!source.isOpen(id)) {
                    ContextReadResult(
                        Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build(),
                        null,
                    )
                } else {
                    val selected = history.history(id, effective, known ?: history.databaseNow())
                    ContextReadResult(
                        Response.ok(selected).header("Cache-Control", "no-store").build(),
                        ContextDisclosure(
                            selected.observations.map { it.evidenceRef },
                            selected.observations.size,
                            selected.truncated,
                        ),
                    )
                }
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        } catch (_: AmlCaseSourceUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }

    private fun timestamp(value: String?, fallback: Instant): Instant = try {
        value?.let(Instant::parse) ?: fallback
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("timestamps must use RFC 3339", exception)
    }

    // Candidate discovery may match an older event outside the bounded related slice.
    // Omit that case rather than return a node with no visible evidence for its edge.
    private fun sharedReferences(root: AmlCaseHistory, related: AmlCaseHistory): Boolean =
        references(root).intersect(references(related)).isNotEmpty()

    private fun references(history: AmlCaseHistory): Set<String> = history.observations.flatMap { row ->
        val evidence = row.evidence
        listOfNotNull(
            "party:${evidence.partyId}",
            evidence.accountId?.let { "account:$it" },
            evidence.transactionId?.let { "transaction:$it" },
        )
    }.toSet()

    private companion object {
        const val PURPOSE = "AML_INVESTIGATION"
        const val RELATED_OBSERVATION_LIMIT = 20
    }
}

/** Visible relationships are bounded by approved assignments, not the whole case store. */
data class AmlCaseNetwork(val root: AmlCaseHistory, val related: List<AmlCaseHistory>)
