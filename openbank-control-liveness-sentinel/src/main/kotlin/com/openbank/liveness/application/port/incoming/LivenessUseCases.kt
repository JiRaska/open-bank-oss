// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.port.incoming

import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.domain.model.LivenessRunReport
import com.openbank.liveness.domain.model.RunTrigger

interface RunLivenessCheckUseCase {
    /**
     * Runs a check and WAITS for the report. Used by the operator trigger, where the caller is a
     * human holding an HTTP connection open and wants the outcome.
     */
    suspend fun run(trigger: RunTrigger): LivenessRunReport

    /**
     * Starts a check and returns its workflow id WITHOUT waiting for the report.
     *
     * This is what the schedule uses. A full run can legitimately take tens of minutes -- the
     * detect activities allow 5 minutes each and the diagnose/propose activity 10, per finding --
     * so waiting inline would hold a scheduler thread for the whole run and make a slow check
     * indistinguishable from a hung one. Temporal already owns the execution and its history is
     * the durable record; the report is persisted by the workflow itself.
     *
     * Idempotent by construction: the workflow id is derived from the trigger and the day, so a
     * pod restart, a second replica, or a manual re-fire on the same day is REJECTED by Temporal
     * rather than starting a duplicate run that would burn the agent's LLM budget twice.
     */
    suspend fun startDetached(trigger: RunTrigger): String
}

interface GetFindingsUseCase {
    suspend fun getActive(): List<LivenessFinding>
    suspend fun getById(id: String): LivenessFinding?
}
