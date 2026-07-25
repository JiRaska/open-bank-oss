// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.agent.domain.policy.PolicyQuery

/**
 * Port for the agent policy engine (ADR-0031 D2). Implementations evaluate a [PolicyQuery]
 * and return a [PolicyDecision]. The contract is deny-by-default and fail-closed: any
 * implementation that cannot reach its backing engine MUST return `allow = false`.
 */
interface PolicyDecisionPoint {
    fun evaluate(query: PolicyQuery): PolicyDecision
}
