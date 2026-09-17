// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import com.openbank.party.domain.model.RepresentationPolicySnapshot
import java.util.UUID

/** Additive evidence store; no update or delete operation exists for a signed rule revision. */
interface RepresentationPolicyRepository {
    suspend fun insert(snapshot: RepresentationPolicySnapshot): RepresentationPolicySnapshot

    suspend fun findById(id: UUID): RepresentationPolicySnapshot?

    suspend fun findBySourceCaseId(sourceCaseId: UUID): RepresentationPolicySnapshot?
}
