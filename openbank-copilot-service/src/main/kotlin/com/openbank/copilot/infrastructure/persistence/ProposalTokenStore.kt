// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.copilot.infrastructure.persistence

import com.openbank.copilot.domain.ProposalToken
import java.util.UUID

/**
 * Port for persisting and retrieving [ProposalToken] instances (ADR-0089 D2, Track A).
 *
 * Two implementations:
 * - [InMemoryProposalTokenStore] — single-node dev/test (active when build property
 *   `copilot.token-store=memory` or in `%test` profile)
 * - [RedisProposalTokenStore] — production-grade Valkey/Redis store (default)
 */
interface ProposalTokenStore {
    /** Persist [token] with TTL [TOKEN_TTL_SECONDS] seconds. */
    fun save(token: ProposalToken)

    /** Retrieve by [id]; returns null if absent or expired. */
    fun find(id: UUID): ProposalToken?

    /** Delete token — must be called after one-time use on confirm. */
    fun delete(id: UUID)

    companion object {
        const val TOKEN_TTL_SECONDS = 300L
    }
}
