// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.it

import com.openbank.kyb.application.port.out.UboObservationAccess
import com.openbank.kyb.application.port.out.UboObservationAccessDecision
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Test-only decision source; an unknown case always fails closed. */
@Alternative
@Priority(1)
@ApplicationScoped
class StubContextOwnershipAccess : UboObservationAccess {
    val decisions = ConcurrentHashMap<UUID, UboObservationAccessDecision>()

    override suspend fun check(caseId: UUID, bearer: String): UboObservationAccessDecision =
        if (bearer.startsWith("Bearer ")) {
            decisions[caseId] ?: UboObservationAccessDecision.DENIED
        } else {
            UboObservationAccessDecision.DENIED
        }
}
