// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.ApprovalGroupRevision
import java.util.UUID

class ConflictingApprovalGroupRevisionException(groupId: UUID, revision: Long) :
    RuntimeException("approval group $groupId revision $revision has conflicting content")

interface ApprovalGroupRevisionRepository {
    suspend fun store(value: ApprovalGroupRevision)
    suspend fun find(groupId: UUID, revision: Long): ApprovalGroupRevision?
}
