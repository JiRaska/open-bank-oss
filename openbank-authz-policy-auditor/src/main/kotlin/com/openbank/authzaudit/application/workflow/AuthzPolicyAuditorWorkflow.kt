// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.AuthzPolicyReport
import com.openbank.authzaudit.domain.model.AuthzPolicySnapshot
import com.openbank.authzaudit.domain.model.RunTrigger
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface AuthzPolicyAuditorWorkflow {
    @WorkflowMethod
    fun runCheck(trigger: RunTrigger): AuthzPolicyReport
}

@ActivityInterface
interface CollectPolicyScanActivity {
    @ActivityMethod
    fun collect(): AuthzPolicySnapshot
}

@ActivityInterface
interface DetectDriftActivity {
    @ActivityMethod
    fun detect(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(finding: AuthzPolicyFinding, contextMetrics: Map<String, Double>): AuthzPolicyFinding

    @ActivityMethod
    fun propose(finding: AuthzPolicyFinding): AuthzPolicyFinding
}
