// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.port.incoming

import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.DocsTruthReport
import com.openbank.docstruth.domain.model.RunTrigger

interface RunDocsTruthCheckUseCase {
    suspend fun run(trigger: RunTrigger): DocsTruthReport
}

interface GetFindingsUseCase {
    suspend fun getActive(): List<DocsTruthFinding>
    suspend fun getById(id: String): DocsTruthFinding?
}
