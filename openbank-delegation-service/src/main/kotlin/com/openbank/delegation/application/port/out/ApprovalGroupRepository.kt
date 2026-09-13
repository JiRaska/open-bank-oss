// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.ApprovalGroup
import com.openbank.libs.domain.event.DomainEvent
import java.util.UUID

interface ApprovalGroupRepository {
    suspend fun create(group: ApprovalGroup, event: DomainEvent): ApprovalGroup
    suspend fun update(group: ApprovalGroup, expectedRevision: Long, event: DomainEvent): ApprovalGroup
    suspend fun findById(id: UUID): ApprovalGroup?
    suspend fun findByScaSessionId(scaSessionId: UUID): ApprovalGroup?
    suspend fun findByOwner(ownerPartyId: UUID): List<ApprovalGroup>
}

class ApprovalGroupConcurrentUpdateException(id: UUID, expectedRevision: Long) :
    RuntimeException("approval group $id changed after revision $expectedRevision")
