// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.workflow

import com.openbank.fx.domain.model.FxConversionStatus
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

@WorkflowInterface
interface FxWorkflow {
    @WorkflowMethod
    fun process(conversionId: UUID): FxConversionStatus
}
