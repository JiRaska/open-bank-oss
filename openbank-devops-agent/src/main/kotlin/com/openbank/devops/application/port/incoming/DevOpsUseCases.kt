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
