// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:package-name")

package com.openbank.delegation.application.port.`in`

import com.openbank.delegation.domain.model.ApprovalGroup
import java.util.UUID

data class CreateApprovalGroupCommand(
    val ownerPartyId: UUID,
    val callerPartyId: CallerPartyId,
    val name: String,
    val members: Set<UUID>,
    val threshold: Int,
    val scaSessionId: UUID,
)

data class ReviseApprovalGroupCommand(
    val id: UUID,
    val ownerPartyId: UUID,
    val callerPartyId: CallerPartyId,
    val expectedRevision: Long,
    val name: String,
    val members: Set<UUID>,
    val threshold: Int,
    val scaSessionId: UUID,
)

interface ApprovalGroupUseCase {
    suspend fun create(command: CreateApprovalGroupCommand): ApprovalGroup
    suspend fun revise(command: ReviseApprovalGroupCommand): ApprovalGroup
    suspend fun deactivate(id: UUID, ownerPartyId: UUID, callerPartyId: CallerPartyId): ApprovalGroup
    suspend fun get(id: UUID, ownerPartyId: UUID, callerPartyId: CallerPartyId): ApprovalGroup
    suspend fun list(ownerPartyId: UUID, callerPartyId: CallerPartyId): List<ApprovalGroup>
}
