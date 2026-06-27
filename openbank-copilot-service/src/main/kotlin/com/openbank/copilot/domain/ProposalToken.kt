// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.copilot.domain

import java.time.Instant
import java.util.UUID

/**
 * Durable handle for a money-path action proposal (ADR-0089 D2, Track A).
 *
 * Issued when the model proposes an action that requires HITL + SCA confirmation.
 * The token is returned to the app as an opaque id; the app exchanges it at the
 * action-confirm endpoint (existing edge SCA flow). Domain class — no framework imports.
 */
data class ProposalToken(
    val id: UUID,
    val toolName: String,
    val params: Map<String, Any>,
    val expiresAt: Instant,
    val customerId: String,
)
