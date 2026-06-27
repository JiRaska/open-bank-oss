// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.application.port.incoming

import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.RunTrigger

interface RunDevOpsAnalysisUseCase {
    suspend fun run(trigger: RunTrigger): DevOpsRunReport
}

interface GetFindingsUseCase {
    suspend fun getActive(): List<DevOpsFinding>
    suspend fun getById(id: String): DevOpsFinding?
}

/**
 * Human-in-the-loop decision on a finding (ADR-0031 D4). The agent proposes; a human disposes — these
 * transitions are driven only by an operator (REST is @RolesAllowed platform-admin), never by the agent.
 * Returns the updated finding, or null if the id is unknown.
 */
interface DecideFindingUseCase {
    suspend fun approve(id: String): DevOpsFinding?
    suspend fun reject(id: String): DevOpsFinding?
}
