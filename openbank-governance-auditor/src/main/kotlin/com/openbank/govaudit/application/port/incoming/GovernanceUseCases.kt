// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.port.incoming

import com.openbank.govaudit.domain.model.GovernanceAuditReport
import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.govaudit.domain.model.RunTrigger

interface RunGovernanceAuditUseCase {
    suspend fun run(trigger: RunTrigger): GovernanceAuditReport
}

interface GetFindingsUseCase {
    suspend fun getActive(): List<GovernanceFinding>
    suspend fun getById(id: String): GovernanceFinding?
}
