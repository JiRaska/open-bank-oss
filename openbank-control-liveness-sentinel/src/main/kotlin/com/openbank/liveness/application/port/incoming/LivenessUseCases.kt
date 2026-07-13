// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.port.incoming

import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.domain.model.LivenessRunReport
import com.openbank.liveness.domain.model.RunTrigger

interface RunLivenessCheckUseCase {
    suspend fun run(trigger: RunTrigger): LivenessRunReport
}

interface GetFindingsUseCase {
    suspend fun getActive(): List<LivenessFinding>
    suspend fun getById(id: String): LivenessFinding?
}
