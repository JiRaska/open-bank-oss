// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyDecision
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyPort
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyQuery
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyUnavailable
import com.openbank.casecoordinator.domain.model.CaseSignalEvidence
import com.openbank.casecoordinator.domain.model.CaseSignalEvidenceStage
import com.openbank.casecoordinator.infrastructure.persistence.CaseSignalEvidenceRepository
import com.openbank.libs.audit.AuditChannel
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant

sealed interface CaseSignalAuthorizationResult {
    data class Authorized(val signalId: String, val rolloutId: String) : CaseSignalAuthorizationResult
    data object Denied : CaseSignalAuthorizationResult
    data object UnknownCase : CaseSignalAuthorizationResult
    data object PolicyUnavailable : CaseSignalAuthorizationResult
}

/** PEP for the two participant capabilities introduced by ADR-0271. */
@ApplicationScoped
class CaseSignalAuthorizationService(
    private val policy: CaseCollaborationPolicyPort,
    private val localGate: CaseCapabilityGate,
    private val evidenceRepository: CaseSignalEvidenceRepository,
    private val auditPublisher: AuditEventPublisher,
) {
    private val clock: Clock = Clock.systemUTC()
    fun authorize(caseId: String, agentId: String, capability: String): CaseSignalAuthorizationResult {
        val context = evidenceRepository.findContext(caseId)
            ?: return CaseSignalAuthorizationResult.UnknownCase
        val signalId = Ids.randomId().toString()
        val decision = try {
            policy.decide(
                CaseCollaborationPolicyQuery(
                    agentId = agentId,
                    capability = capability,
                    caseClass = context.caseClass,
                    deliveryMode = context.deliveryMode,
                ),
            )
        } catch (_: CaseCollaborationPolicyUnavailable) {
            audit(caseId, agentId, capability, AuditResult.FAILURE, signalId, reason = "policy unavailable")
            return CaseSignalAuthorizationResult.PolicyUnavailable
        }

        var evaluation = evaluate(agentId, capability, decision)
        val authorizationEvidence = CaseSignalEvidence(
            signalId = signalId,
            caseId = caseId,
            agentId = agentId,
            capability = capability,
            stage = CaseSignalEvidenceStage.AUTHORIZED,
            observedAtEpochMs = Instant.now(clock).toEpochMilli(),
            rolloutId = decision.rolloutId,
            policyDecisionId = decision.decisionId,
            policyReason = evaluation.reason,
        )
        if (evaluation.allowed &&
            !evidenceRepository.tryRecordAuthorized(authorizationEvidence, decision.maxSignalsPerCase)
        ) {
            evaluation = PolicyEvaluation(false, "per-case signal quota exhausted")
        }
        return recordDecision(caseId, agentId, capability, signalId, decision, evaluation)
    }

    private fun recordDecision(
        caseId: String,
        agentId: String,
        capability: String,
        signalId: String,
        decision: CaseCollaborationPolicyDecision,
        evaluation: PolicyEvaluation,
    ): CaseSignalAuthorizationResult {
        val stage = if (evaluation.allowed) {
            CaseSignalEvidenceStage.AUTHORIZED
        } else {
            CaseSignalEvidenceStage.DENIED
        }
        if (!evaluation.allowed) {
            evidenceRepository.record(
                CaseSignalEvidence(
                    signalId = signalId,
                    caseId = caseId,
                    agentId = agentId,
                    capability = capability,
                    stage = stage,
                    observedAtEpochMs = Instant.now(clock).toEpochMilli(),
                    rolloutId = decision.rolloutId,
                    policyDecisionId = decision.decisionId,
                    policyReason = evaluation.reason,
                ),
            )
        }
        audit(
            caseId,
            agentId,
            capability,
            if (evaluation.allowed) AuditResult.SUCCESS else AuditResult.DENIED,
            signalId,
            decision.decisionId,
            decision.rolloutId,
            evaluation.reason,
        )
        return if (evaluation.allowed) {
            CaseSignalAuthorizationResult.Authorized(signalId, decision.rolloutId)
        } else {
            CaseSignalAuthorizationResult.Denied
        }
    }

    private fun evaluate(
        agentId: String,
        capability: String,
        decision: CaseCollaborationPolicyDecision,
    ): PolicyEvaluation {
        val locallyAllowed = when (capability) {
            "case.join" -> localGate.canJoinCase(agentId)
            "case.contribute" -> localGate.canContribute(agentId)
            else -> false
        }
        val quotaConfigured = decision.maxSignalsPerCase > 0
        val reason = when {
            !decision.allow -> decision.reason
            !locallyAllowed -> "local charter gate denied"
            !quotaConfigured -> "signal quota absent from policy decision"
            else -> decision.reason
        }
        return PolicyEvaluation(decision.allow && locallyAllowed && quotaConfigured, reason)
    }

    fun recordInvoked(
        caseId: String,
        agentId: String,
        capability: String,
        authorization: CaseSignalAuthorizationResult.Authorized,
    ) {
        evidenceRepository.record(
            CaseSignalEvidence(
                signalId = authorization.signalId,
                caseId = caseId,
                agentId = agentId,
                capability = capability,
                stage = CaseSignalEvidenceStage.INVOKED,
                observedAtEpochMs = Instant.now(clock).toEpochMilli(),
                rolloutId = authorization.rolloutId,
            ),
        )
    }

    private fun audit(
        caseId: String,
        agentId: String,
        capability: String,
        result: AuditResult,
        signalId: String,
        decisionId: String = "",
        rolloutId: String = "",
        reason: String,
    ) {
        runBlocking {
            auditPublisher.publish(
                AuditEvent(
                    actorId = agentId,
                    actorType = "AI_AGENT",
                    operation = capability,
                    resourceType = "agent-case",
                    resourceId = caseId,
                    timestamp = Instant.now(clock),
                    result = result,
                    traceId = caseId,
                    channel = AuditChannel.API,
                    payload = mapOf(
                        "signal_id" to signalId,
                        "policy_decision_id" to decisionId,
                        "rollout_id" to rolloutId,
                        "policy_reason" to reason,
                    ),
                ),
            )
        }
    }

    private data class PolicyEvaluation(val allowed: Boolean, val reason: String)
}
