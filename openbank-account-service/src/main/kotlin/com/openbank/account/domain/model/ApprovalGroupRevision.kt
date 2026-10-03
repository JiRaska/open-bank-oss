// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import java.util.UUID

/** Immutable historical roster; operations will snapshot one exact revision (ADR-0284 D3). */
data class ApprovalGroupRevision(
    val groupId: UUID,
    val ownerPartyId: UUID,
    val revision: Long,
    val name: String,
    val members: Set<UUID>,
    val threshold: Int,
    val active: Boolean,
) {
    init {
        require(revision >= 1)
        require(members.isNotEmpty())
        require(threshold in 1..members.size)
        require(ownerPartyId !in members)
    }
}
