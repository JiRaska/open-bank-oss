// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.application.port.out

import com.openbank.copilot.domain.ProposalToken
import java.util.UUID

/**
 * Outbound port for persisting and retrieving [ProposalToken] instances (ADR-0089 D2, Track A).
 *
 * **[save] has no caller in `src/main`** (#5900): no production code issues a [ProposalToken], so
 * both adapters below are only ever read from and [find] always misses. See [ProposalToken] for why
 * the live HITL path does not go through this store and what building Track A would take.
 *
 * Two adapters:
 * - `infrastructure.persistence.InMemoryProposalTokenStore` — single-node dev/test (active when
 *   build property `copilot.token-store=memory` or in the `%test` profile)
 * - `infrastructure.persistence.RedisProposalTokenStore` — production-grade Valkey/Redis (default)
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
