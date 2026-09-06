// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import com.openbank.govaudit.infrastructure.config.GovernanceAuditorConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The money-path set is parsed out of ONE comma-separated config string, and every service name in
 * it is what `DetectViolationsActivityImpl` compares `pr.changedServices` against — so a name left
 * with surrounding whitespace silently never matches, and the money-path obligation (2 approvals +
 * a threat model) is downgraded to the default one with no error anywhere. These pin the parse.
 */
class GovernanceRulesReadAdapterTest {

    private fun adapterFor(
        moneyPathServices: String,
        defaultApprovals: Int = 1,
        moneyPathApprovals: Int = 2,
    ): GovernanceRulesReadAdapter {
        val config = mockk<GovernanceAuditorConfig> {
            every { moneyPathServices() } returns moneyPathServices
            every { defaultApprovals() } returns defaultApprovals
            every { moneyPathApprovals() } returns moneyPathApprovals
        }
        return GovernanceRulesReadAdapter(config)
    }

    @Test
    fun `whitespace around a service name is trimmed, so the name still matches a changed service`(): Unit =
        runBlocking {
            val services = adapterFor(" openbank-ledger-service ,\topenbank-fx-service\n").moneyPathServices()

            assertThat(services).containsExactlyInAnyOrder("openbank-ledger-service", "openbank-fx-service")
        }

    @Test
    fun `an empty element produced by a trailing or doubled comma is dropped, not kept as an empty name`():
        Unit = runBlocking {
        val services = adapterFor("openbank-ledger-service,,openbank-fx-service,").moneyPathServices()

        assertThat(services).doesNotContain("")
        assertThat(services).hasSize(2)
    }

    @Test
    fun `an entirely blank config yields an empty set rather than a set holding one empty name`(): Unit =
        runBlocking {
            assertThat(adapterFor("").moneyPathServices()).isEmpty()
            assertThat(adapterFor("   ").moneyPathServices()).isEmpty()
        }

    @Test
    fun `a duplicated service name collapses because the result is a set`(): Unit = runBlocking {
        val services = adapterFor("openbank-ledger-service,openbank-ledger-service").moneyPathServices()

        assertThat(services).hasSize(1)
    }

    @Test
    fun `the approval thresholds are the ones a money-path PR is judged against`(): Unit = runBlocking {
        val adapter = adapterFor(moneyPathServices = "", defaultApprovals = 1, moneyPathApprovals = 2)

        assertThat(adapter.defaultApprovals()).isEqualTo(1)
        assertThat(adapter.moneyPathApprovals()).isEqualTo(2)
        assertThat(adapter.moneyPathApprovals()).isGreaterThan(adapter.defaultApprovals())
    }
}
