// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class ProposalTokenLogRefTest {

    @Test
    fun `log ref is stable, prefixed and never contains the token id`() {
        val id = UUID.fromString("3f2b8c1e-0a4d-4e7b-9c51-6d2f0e8a7b13")

        val ref = ProposalToken.logRef(id)

        assertThat(ref).matches("ptk_[0-9a-f]{12}")
        assertThat(ProposalToken.logRef(id)).isEqualTo(ref)
        assertThat(ref).doesNotContain(id.toString())
        id.toString().split('-').forEach { assertThat(ref).doesNotContain(it) }
    }

    @Test
    fun `distinct tokens get distinct refs`() {
        val refs = (1..1_000).map { ProposalToken.logRef(UUID.randomUUID()) }.toSet()

        assertThat(refs).hasSize(1_000)
    }

    /**
     * A proposal token id is a one-time capability, so no log line in this service may carry it in
     * the clear. Any `token=` field in a log format string must be the [ProposalToken.logRef]
     * `token_ref=` form instead.
     */
    @Test
    fun `no production log line formats a raw proposal token id`() {
        val root = File("src/main/kotlin")
        assertThat(root).isDirectory()
        val rawTokenField = Regex("""\btoken(Id)?=%s""")

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (rawTokenField.containsMatchIn(line)) "${file.path}:${i + 1}" else null
                }
            }
            .toList()

        assertThat(offenders).isEmpty()
    }
}
