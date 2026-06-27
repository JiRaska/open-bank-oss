// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.application

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
