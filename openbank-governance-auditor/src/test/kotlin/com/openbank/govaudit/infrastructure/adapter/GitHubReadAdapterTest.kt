// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The read adapter is still a bootstrap stub, and its KDoc makes a SAFETY claim about the stub
 * values: `threatModelExists` fails CLOSED (reports "missing"), so an unwired adapter can only ever
 * over-report a violation, never silently suppress a true one. That direction is the whole point —
 * returning `true` here would make every money-path PR look compliant — so it is asserted rather
 * than left to the comment.
 */
class GitHubReadAdapterTest {

    private val adapter = GitHubReadAdapter()

    @Test
    fun `threatModelExists fails CLOSED for every service while the read path is unwired`(): Unit = runBlocking {
        assertThat(adapter.threatModelExists("openbank-ledger-service")).isFalse()
        assertThat(adapter.threatModelExists("openbank-notification-service")).isFalse()
        assertThat(adapter.threatModelExists("")).isFalse()
    }

    @Test
    fun `listMergedPrsSince returns an empty list, never a fabricated PR`(): Unit = runBlocking {
        assertThat(adapter.listMergedPrsSince(Instant.parse("2026-01-01T00:00:00Z"))).isEmpty()
        assertThat(adapter.listMergedPrsSince(Instant.EPOCH)).isEmpty()
    }
}
