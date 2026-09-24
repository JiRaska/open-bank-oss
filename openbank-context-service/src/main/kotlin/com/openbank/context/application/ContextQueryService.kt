// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
import com.openbank.context.domain.ImpactProjectionStatus
import com.openbank.context.domain.IncidentImpact
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import com.openbank.libs.authz.ResourceRef
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest
import java.time.Clock

@ApplicationScoped
@Suppress("LongParameterList", "TooManyFunctions")
class ContextQueryService(
    private val graph: ContextGraphPort,
    private val assignments: CaseAssignmentPort,
    private val audit: ContextReadAuditPort,
    private val pdp: PolicyDecisionPoint,
    private val clock: Clock,
    private val meters: MeterRegistry,
    @ConfigProperty(name = "openbank.context.max-nodes") private val maxNodes: Int,
    @ConfigProperty(name = "openbank.context.max-edges") private val maxEdges: Int,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.projection-generation") private val projectionGeneration: Long,
) {
    internal suspend fun <T> authorizationEvidence(
        ref: String,
        actor: Investigator,
        context: InvestigationContext,
        block: suspend () -> ContextReadResult<T>,
    ): T = authorized(
        "context.authorization.read",
        "AUTHORIZATION_REVIEW",
        ContextNamespace.AUTHORIZATION,
        "delegation:$ref",
        actor,
        context,
        block,
    )

    internal suspend fun <T> amlCaseEvidence(
        ref: String,
        actor: Investigator,
        context: InvestigationContext,
        block: suspend () -> ContextReadResult<T>,
    ): T = authorized(
        "context.aml-case.read",
        "AML_INVESTIGATION",
        ContextNamespace.AML,
        "aml-case:$ref",
        actor,
        context,
        block,
    )

    internal suspend fun <T> kybCaseEvidence(
        ref: String,
        actor: Investigator,
        context: InvestigationContext,
        block: suspend () -> ContextReadResult<T>,
    ): T = authorized(
        "context.kyb-case.read",
        "KYB_OWNERSHIP_REVIEW",
        ContextNamespace.KYB,
        "kyb-case:$ref",
        actor,
        context,
        block,
    )

    internal suspend fun <T> fraudCaseEvidence(
        ref: String,
        actor: Investigator,
        context: InvestigationContext,
        block: suspend () -> ContextReadResult<T>,
    ): T = authorized(
        "context.fraud-case.read",
        "FRAUD_INVESTIGATION",
        ContextNamespace.FRAUD,
        "fraud-case:$ref",
        actor,
        context,
        block,
    )

    suspend fun complaint(ref: String, actor: Investigator, context: InvestigationContext): ContextNeighborhood? =
        authorized(
            "context.complaint.read",
            "PAYMENT_COMPLAINT",
            ContextNamespace.COMPLAINT,
            "complaint:$ref",
            actor,
            context,
        ) {
            val view = graph.neighborhood(
                ContextNamespace.COMPLAINT,
                "complaint:$ref",
                context.asOf,
                maxNodes,
                maxEdges,
            )
            ContextReadResult(
                view,
                view?.let {
                    val references = it.nodes.map { node ->
                        "node:${node.sourceSystem}:${node.sourceRef}:${node.sourceVersion}"
                    } + it.edges.map { edge -> edge.evidenceRef }
                    ContextDisclosure(
                        references,
                        references.size,
                        it.truncated,
                        projectionGeneration,
                    )
                },
            )
        }

    suspend fun incident(ref: String, actor: Investigator, context: InvestigationContext): IncidentImpact = authorized(
        "context.incident.aggregate.read",
        "INCIDENT_IMPACT",
        ContextNamespace.INCIDENT,
        "incident:$ref",
        actor,
        context,
    ) {
        val view = graph.neighborhood(ContextNamespace.INCIDENT, "incident:$ref", context.asOf, maxNodes, maxEdges)
        val affected = view?.nodes.orEmpty().filter {
            it.key != "incident:$ref"
        }.groupingBy { it.type }.eachCount().toSortedMap()
        val evidenceRefs = view?.edges.orEmpty().map { it.evidenceRef }.distinct()
        val status = when {
            view == null -> ImpactProjectionStatus.MISSING
            view.truncated -> ImpactProjectionStatus.PARTIAL
            else -> ImpactProjectionStatus.AVAILABLE
        }
        ContextReadResult(
            IncidentImpact(ref, affected, affected.values.sum(), drilldownAvailable = false, projectionStatus = status),
            ContextDisclosure(
                evidenceRefs,
                maxOf(affected.values.sum(), evidenceRefs.size),
                view?.truncated ?: false,
                projectionGeneration,
            ),
        )
    }

    @Suppress("ThrowsCount")
    private suspend fun <T> authorized(
        action: String,
        requiredPurpose: String,
        namespace: ContextNamespace,
        root: String,
        actor: Investigator,
        context: InvestigationContext,
        block: suspend () -> ContextReadResult<T>,
    ): T {
        val started = System.nanoTime()
        try {
            return authorizeAndRead(action, requiredPurpose, namespace, root, actor, context, block)
        } finally {
            meters.timer("openbank_context_read_duration", "action", action)
                .record(java.time.Duration.ofNanos(System.nanoTime() - started))
        }
    }

    @Suppress("ThrowsCount", "LongMethod")
    private suspend fun <T> authorizeAndRead(
        action: String,
        requiredPurpose: String,
        namespace: ContextNamespace,
        root: String,
        actor: Investigator,
        context: InvestigationContext,
        block: suspend () -> ContextReadResult<T>,
    ): T {
        val now = clock.instant()
        if (context.purpose != requiredPurpose) {
            audit.record(entry(actor, context, action, root, "DENIED", null, "PURPOSE_MISMATCH", now))
            decisionMetric(action, "denied", "purpose_mismatch")
            throw ContextAccessDenied()
        }
        val assigned = assignments.isAssignedToRoot(actor.id, context.caseId, context.purpose, root, now)
        if (!assigned) {
            audit.record(entry(actor, context, action, root, "DENIED", null, "NO_ACTIVE_ASSIGNMENT", now))
            decisionMetric(action, "denied", "no_active_assignment")
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
                        "rootScopeVerified" to true,
                        "effectiveAt" to context.asOf.toString(),
                        "knownAt" to context.knownAt?.toString(),
                        "bankScope" to bankScope,
                    ),
                ),
            )
        } catch (_: Exception) {
            audit.record(entry(actor, context, action, root, "UNAVAILABLE", null, "PDP_UNAVAILABLE", now))
            decisionMetric(action, "unavailable", "pdp_unavailable")
            throw ContextAuthorizationUnavailable()
        }
        if (!decision.allow) {
            audit.record(entry(actor, context, action, root, "DENIED", decision.policyVersion, "POLICY_DENIED", now))
            decisionMetric(action, "denied", "policy_denied")
            throw ContextAccessDenied()
        }
        val allowed = entry(actor, context, action, root, "ALLOWED", decision.policyVersion, "POLICY_ALLOWED", now)
        audit.record(allowed)
        decisionMetric(action, "allowed", "policy_allowed")
        val result = block()
        result.disclosure?.let { disclosure ->
            require(
                disclosure.evidenceCount >= disclosure.evidenceRefs.size &&
                    disclosure.evidenceRefs.size <= MAX_EVIDENCE_REFS,
            ) {
                "invalid disclosure evidence count"
            }
            audit.recordDisclosure(
                ContextDisclosureAudit(allowed.id, queryHash(action, root, context), disclosure, clock.instant()),
            )
        }
        return result.value
    }

    private fun queryHash(action: String, root: String, context: InvestigationContext): String {
        val fields =
            listOf(
                bankScope,
                action,
                root,
                context.caseId,
                context.purpose,
                context.asOf.toString(),
                context.knownAt?.toString().orEmpty(),
            )
        val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun decisionMetric(action: String, decision: String, reason: String) = meters.counter(
        "openbank_context_access_decisions_total",
        "action",
        action,
        "decision",
        decision,
        "reason",
        reason,
    )
        .increment()

    private fun entry(
        actor: Investigator,
        c: InvestigationContext,
        action: String,
        root: String,
        decision: String,
        version: String?,
        reason: String,
        now: java.time.Instant,
    ) = ContextReadAudit(actor.id, c.caseId, c.purpose, action, root, decision, version, reason, now, c.asOf, c.knownAt)

    private companion object {
        const val MAX_EVIDENCE_REFS = 512
    }
}
