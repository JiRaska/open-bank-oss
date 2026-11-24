// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.context.RequestScoped
import jakarta.enterprise.inject.Instance
import org.jboss.logging.Logger

/**
 * The four-eyes approval [AuthorizeInterceptor] consumed for THIS request, or `null` when four-eyes
 * did not run (the PDP did not flag `four_eyes_required`) or the call was paused instead.
 *
 * Lets a resource that acts on an approval bind it to the current call. An approval's status alone
 * cannot: `EXECUTED` is also what an approval consumed by an EARLIER call looks like, so a body that
 * trusted the status would accept a replayed id whenever the policy stopped gating the action
 * (#16455 review). Request-scoped, so it can never carry over from one request to the next.
 */
@RequestScoped
class FourEyesConsumption {
    var consumedApprovalId: String? = null
        internal set
}

/**
 * Records [approvalId] as consumed by the current request. Outside an HTTP request (batch/timer)
 * there is no request scope and no resource body that could ask, so nothing is recorded.
 */
internal fun Instance<FourEyesConsumption>?.record(approvalId: String) {
    if (this == null || !isResolvable) return
    try {
        get().consumedApprovalId = approvalId
    } catch (_: ContextNotActiveException) {
        // Fail-closed (a resource that binds to it refuses), but say so: otherwise it is a silent 403.
        Logger.getLogger(FourEyesConsumption::class.java)
            .warnf("four-eyes: approval id=%s consumed outside a request context — not recorded", approvalId)
    }
}
