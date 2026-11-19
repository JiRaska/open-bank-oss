// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import java.time.Instant
import java.util.UUID

interface StatutoryDelegationSca {
    /** Consume once or recover the recorded receipt for the same frozen statutory approval. */
    suspend fun consume(operation: StatutoryDelegationOperation, actorPartyId: UUID, challengeId: UUID): Instant
}
