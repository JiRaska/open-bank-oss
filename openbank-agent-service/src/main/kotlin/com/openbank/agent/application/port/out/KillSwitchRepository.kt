// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import com.openbank.agent.domain.control.HaltStatus
import java.time.Instant

/**
 * Outbound port: the runtime break-glass halt table behind the kill switch (ADR-0031 D7).
 *
 * Implemented by [com.openbank.agent.infrastructure.persistence.JdbcKillSwitchRepository].
 * The *config* baseline half of the kill switch (`agents.yaml` / `agent.kill-switch.*`) is not
 * this port's business — [com.openbank.agent.application.KillSwitchService] combines the two.
 */
interface KillSwitchRepository {

    /** Idempotently suspend [scope] (an agent id, or `*` for every agent). */
    fun upsertHalt(scope: String, reason: String, setBy: String, setAt: Instant)

    /** Lift the runtime halt on [scope]. A no-op when nothing was halted. */
    fun deleteHalt(scope: String)

    /** The active halt on [scope], or null when it is not halted. */
    fun findHalt(scope: String): HaltStatus?

    /** Every active runtime halt, newest first. */
    fun listHalts(): List<HaltStatus>
}
