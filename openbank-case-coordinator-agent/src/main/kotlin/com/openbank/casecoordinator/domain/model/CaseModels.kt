// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.domain.model

/**
 * Domain model for an agent-swarm case (ADR-0244). Pure Kotlin — zero framework imports
 * (ADR-0002/ADR-0122). Every type crossing the Temporal boundary (workflow arguments, signal
 * payloads, activity inputs/outputs) is a Kotlin data class serialized with the kotlin-aware
 * Jackson converter the shared TemporalClientProducer builds.
 */

/** Case classes declared in `agents.yaml: case_classes`. Only [INCIDENT_RESPONSE] is the
 * ADR-0244 pilot; the money-path classes stay config-disabled until their threat model
 * lands (ADR-0030). */
enum class CaseClass {
    INCIDENT_RESPONSE,
    FRAUD_INVESTIGATION,
    AML_ALERT,
}

enum class CaseStatus {
    OPEN,
    CONVERGING,
    CONTESTED,
    SYNTHESIZED,
    CLOSED,
}

/** Delivery is explicit in the workflow input so replay cannot depend on mutable deployment config. */
enum class CaseDeliveryMode { HITL, SHADOW }

/** Start parameters for a case workflow run. */
data class CaseStart(
    val caseId: String,
    val caseClass: CaseClass,
    val subjectRef: String,
    val openedBy: String,
    val dispositionTarget: String,
    val deadlineEpochMs: Long,
    val contestedRateThreshold: Double,
    val maxContributions: Int,
    val deliveryMode: CaseDeliveryMode = CaseDeliveryMode.HITL,
)

data class JoinSignal(val agentId: String, val role: String, val signalId: String = "", val rolloutId: String = "")

data class ContributeSignal(
    val agentId: String,
    val summary: String,
    val evidenceRefs: List<String>,
    val contested: Boolean,
    val signalId: String = "",
    val rolloutId: String = "",
)

/**
 * Deterministic pre-emption (ADR-0244 D5): newer evidence invalidates the in-flight draft.
 * The workflow bumps its draft version; only contributions of the final draft feed synthesis.
 */
data class SupersedeSignal(val agentId: String, val newEvidenceRef: String, val reason: String)

data class SynthesisRequest(val agentId: String)

/** One recorded contribution. [draftVersion] pins it to the draft it was made against. */
data class Contribution(
    val agentId: String,
    val summary: String,
    val evidenceRefs: List<String>,
    val contested: Boolean,
    val draftVersion: Int,
    val signalId: String = "",
    val rolloutId: String = "",
)

enum class CaseSignalEvidenceStage { AUTHORIZED, DENIED, INVOKED, CONSUMED, PERSISTED }

/** Metadata-only collaboration evidence; summaries and evidence contents never enter this trail. */
data class CaseSignalEvidence(
    val signalId: String,
    val caseId: String,
    val agentId: String,
    val capability: String,
    val stage: CaseSignalEvidenceStage,
    val observedAtEpochMs: Long,
    val rolloutId: String = "",
    val policyDecisionId: String = "",
    val policyReason: String = "",
)

/** Read model exposed via the workflow query method — the Phase 2 API projects this. */
data class CaseState(
    val caseId: String,
    val caseClass: CaseClass,
    val status: CaseStatus,
    val participants: List<String>,
    val contributionCount: Int,
    val contestedCount: Int,
    val draftVersion: Int,
    val openedAtEpochMs: Long,
    val deadlineEpochMs: Long,
)

/** Terminal workflow result. Every case emits exactly one HITL proposal (ADR-0244 D7). */
data class CaseOutcome(
    val caseId: String,
    val status: CaseStatus,
    val proposalId: String,
    val proposalSummary: String,
    val contributionCount: Int,
)
