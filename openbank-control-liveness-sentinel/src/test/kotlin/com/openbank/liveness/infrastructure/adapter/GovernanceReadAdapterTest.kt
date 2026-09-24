// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The rules.yaml allowlist read is still a stub, and the direction it fails in is the contract:
 * an EMPTY allowlist means every producer-only topic and every unverified lineage edge is still
 * surfaced, just without the "already tracked" distinction. A non-empty stub -- or one that threw
 * -- would suppress real findings instead, which is the failure this agent exists to catch.
 */
class GovernanceReadAdapterTest {

    private val adapter = GovernanceReadAdapter()

    @Test
    fun `the event-consumer allowlist is empty, so nothing is silently suppressed`(): Unit = runBlocking {
        assertThat(adapter.eventConsumerAllowlist()).isEmpty()
    }

    @Test
    fun `the lineage allowlist is empty, so nothing is silently suppressed`(): Unit = runBlocking {
        assertThat(adapter.lineageAllowlist()).isEmpty()
    }
}
