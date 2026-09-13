// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
import com.openbank.context.domain.IncidentImpact
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import com.openbank.libs.authz.ResourceRef
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock

@ApplicationScoped
class ContextQueryService(
    private val graph: ContextGraphPort,
    private val assignments: CaseAssignmentPort,
    private val audit: ContextReadAuditPort,
    private val pdp: PolicyDecisionPoint,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.context.max-nodes") private val maxNodes: Int,
    @ConfigProperty(name = "openbank.context.max-edges") private val maxEdges: Int,
) {
    suspend fun complaint(ref: String, actor: Investigator, context: InvestigationContext): ContextNeighborhood? =
        authorized("context.complaint.read", ContextNamespace.COMPLAINT, "complaint:$ref", actor, context) {
            graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$ref", context.asOf, maxNodes, maxEdges)
        }

    suspend fun incident(ref: String, actor: Investigator, context: InvestigationContext): IncidentImpact =
        authorized("context.incident.aggregate.read", ContextNamespace.INCIDENT, "incident:$ref", actor, context) {
            val view = graph.neighborhood(ContextNamespace.INCIDENT, "incident:$ref", context.asOf, maxNodes, maxEdges)
            val affected = view?.nodes.orEmpty().filter {
                it.key != "incident:$ref"
            }.groupingBy { it.type }.eachCount().toSortedMap()
            IncidentImpact(ref, affected, affected.values.sum(), drilldownAvailable = false)
        }

    @Suppress("ThrowsCount")
    private suspend fun <T> authorized(
        action: String,
        namespace: ContextNamespace,
        root: String,
        actor: Investigator,
        context: InvestigationContext,
        block: suspend () -> T,
    ): T {
        val now = clock.instant()
        if (!assignments.isAssigned(actor.id, context.caseId, context.purpose, now)) {
            audit.record(entry(actor, context, action, root, "DENIED", null, "NO_ACTIVE_ASSIGNMENT", now))
            throw ContextAccessDenied()
        }
        val decision = try {
            pdp.allow(
                AuthzQuery(
                    principal = Principal(actor.id, "HUMAN", actor.roles),
                    action = action,
                    resource = ResourceRef(namespace.name.lowercase(), root),
                    attributes = mapOf(
                        "caseId" to context.caseId,
                        "purpose" to context.purpose,
                        "assignmentVerified" to true,
                    ),
                ),
            )
        } catch (_: Exception) {
            audit.record(entry(actor, context, action, root, "UNAVAILABLE", null, "PDP_UNAVAILABLE", now))
            throw ContextAuthorizationUnavailable()
        }
        if (!decision.allow) {
            audit.record(entry(actor, context, action, root, "DENIED", decision.policyVersion, "POLICY_DENIED", now))
            throw ContextAccessDenied()
        }
        audit.record(entry(actor, context, action, root, "ALLOWED", decision.policyVersion, "POLICY_ALLOWED", now))
        return block()
    }

    private fun entry(
        actor: Investigator,
        c: InvestigationContext,
        action: String,
        root: String,
        decision: String,
        version: String?,
        reason: String,
        now: java.time.Instant,
    ) = ContextReadAudit(actor.id, c.caseId, c.purpose, action, root, decision, version, reason, now)
}
