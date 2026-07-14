// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.port.incoming

import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import com.openbank.releasesteward.domain.model.ReleaseStewardReport
import com.openbank.releasesteward.domain.model.RunTrigger

interface RunReleaseStewardCheckUseCase {
    suspend fun run(trigger: RunTrigger): ReleaseStewardReport
}

interface GetFindingsUseCase {
    suspend fun getActive(): List<ReleaseStewardFinding>
    suspend fun getById(id: String): ReleaseStewardFinding?
}
