// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.application.port.out

import com.openbank.libs.governance.Proposal
import com.openbank.notification.domain.ops.DispatchControlSnapshot
import com.openbank.notification.domain.ops.ResumeAction

/**
 * Persistence port for the dispatch-control plane. The store is the single source of truth all
 * replicas converge on (ADR-0047); state rows are append-only (versioned), proposals carry the
 * four-eyes decision trail.
 */
interface DispatchControlStore {
    suspend fun current(controlKey: String): DispatchControlSnapshot?
    suspend fun append(snapshot: DispatchControlSnapshot)
    suspend fun history(controlKey: String, limit: Int): List<DispatchControlSnapshot>
    suspend fun saveProposal(proposal: Proposal<ResumeAction>)
    suspend fun findProposal(id: String): Proposal<ResumeAction>?
}
