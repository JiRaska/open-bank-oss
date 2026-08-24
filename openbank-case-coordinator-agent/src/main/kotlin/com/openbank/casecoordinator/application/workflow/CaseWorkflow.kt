// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application.workflow

import com.openbank.casecoordinator.domain.model.CaseOutcome
import com.openbank.casecoordinator.domain.model.CaseSignalEvidence
import com.openbank.casecoordinator.domain.model.CaseStart
import com.openbank.casecoordinator.domain.model.CaseState
import com.openbank.casecoordinator.domain.model.ContributeSignal
import com.openbank.casecoordinator.domain.model.Contribution
import com.openbank.casecoordinator.domain.model.JoinSignal
import com.openbank.casecoordinator.domain.model.SupersedeSignal
import com.openbank.casecoordinator.domain.model.SynthesisRequest
import io.temporal.activity.ActivityInterface
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Agent-swarm case workflow (ADR-0244). Several chartered agents join a running case, contribute
 * findings, pre-empt each other via `superseded-by-evidence` (D5), and the case converges to
 * exactly one HITL proposal (D7) — emitted on synthesis, on contest, and on timeout alike.
 */
@WorkflowInterface
interface CaseWorkflow {

    @WorkflowMethod(name = "CaseWorkflow")
    fun run(start: CaseStart): CaseOutcome

    @SignalMethod
    fun join(signal: JoinSignal)

    @SignalMethod
    fun contribute(signal: ContributeSignal)

    @SignalMethod
    fun supersede(signal: SupersedeSignal)

    @SignalMethod
    fun requestSynthesis(request: SynthesisRequest)

    /** Phase 2's read API projects this; kept on the workflow so history and state never fork. */
    @QueryMethod
    fun state(): CaseState
}

/** LLM convergence synthesis (D2/D5) — the only non-deterministic judgement in the flow. */
@ActivityInterface
interface CaseSynthesisActivity {
    fun synthesize(caseId: String, caseClass: String, contributions: List<Contribution>): String?
}

/** Emits the single HITL proposal into the case outbox (D7). */
@ActivityInterface
interface CaseProposalActivity {
    /** Legacy activity name and payload; never change while old workflows can replay. */
    fun emitProposal(caseId: String, proposalType: String, summary: String, contested: Boolean): String
}

/** New activity type keeps shadow delivery out of legacy Temporal histories. */
@ActivityInterface
interface CaseProposalDeliveryActivity {
    fun emitProposalWithDelivery(
        caseId: String,
        proposalType: String,
        summary: String,
        contested: Boolean,
        shadow: Boolean,
    ): String
}

/** Persists case lifecycle + contribution rows to the V1 schema. */
@ActivityInterface
interface CasePersistenceActivity {
    fun recordCaseOpened(start: CaseStart, openedAtEpochMs: Long)

    fun recordContributions(caseId: String, contributions: List<Contribution>)

    fun recordSignalEvidence(evidence: List<CaseSignalEvidence>)

    fun recordCaseClosed(caseId: String, status: String, closedAtEpochMs: Long)
}
