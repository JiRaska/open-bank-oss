// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

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
 * transitions are driven only by an operator (REST is @RolesAllowed ROLE_ADMIN), never by the agent.
 * Returns the updated finding, or null if the id is unknown.
 */
interface DecideFindingUseCase {
    suspend fun approve(id: String): DevOpsFinding?
    suspend fun reject(id: String): DevOpsFinding?
}
