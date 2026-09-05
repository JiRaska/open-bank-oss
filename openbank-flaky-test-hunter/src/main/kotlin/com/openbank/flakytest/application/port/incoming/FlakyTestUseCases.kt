// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.port.incoming

import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.FlakyTestReport
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.flakytest.domain.model.TestIntelligenceAnalysisRequest

interface RunFlakyTestCheckUseCase {
    /** Runs a sweep and WAITS for the report — the operator trigger, where a human is holding
     *  an HTTP connection open and wants the outcome. */
    suspend fun run(trigger: RunTrigger): FlakyTestReport

    /**
     * Starts a sweep and returns its workflow id WITHOUT waiting.
     *
     * What the schedule uses. A sweep runs several collect activities, drift detectors and an LLM
     * diagnosis per finding, so it can take many minutes; waiting inline would pin a scheduler
     * thread for the whole run and make a slow sweep indistinguishable from a hung one. Temporal
     * owns the execution and its history is the durable record.
     *
     * Idempotent: the workflow id is derived from the trigger and the UTC day, so a pod restart,
     * operator retry or second replica is REJECTED by Temporal rather than starting a duplicate
     * sweep that would spend the agent's daily LLM budget twice. An operator may pin the current
     * or previous UTC day with a bounded idempotency key; null preserves old clients by selecting
     * the current UTC day.
     */
    suspend fun startDetached(trigger: RunTrigger, idempotencyKey: String? = null): String
}

interface GetFindingsUseCase {
    suspend fun getActive(): List<FlakyTestFinding>
    suspend fun getById(id: String): FlakyTestFinding?
}

/** Bounded evidence analysis. It may create reviewable findings, never a remediation. */
interface AnalyzeTestIntelligenceUseCase {
    suspend fun analyze(request: TestIntelligenceAnalysisRequest): List<FlakyTestFinding>
}
