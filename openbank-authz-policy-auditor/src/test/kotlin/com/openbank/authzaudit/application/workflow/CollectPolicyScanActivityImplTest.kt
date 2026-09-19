// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.authzaudit.application.port.out.PolicyScanPort
import com.openbank.authzaudit.domain.model.AuthzPolicySnapshot
import com.openbank.authzaudit.domain.model.PrincipalTypeComparison
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The collect activity must be a pure pass-through of the scan port: any judging it did here would
 * be judging Temporal cannot replay, and any swallowing would turn an unreadable repo checkout
 * (the sidecar mount missing, say) into a snapshot of zero signals — which every downstream
 * detector reads as "the fleet is clean".
 */
class CollectPolicyScanActivityImplTest {

    private class SyncCollect(port: PolicyScanPort) : CollectPolicyScanActivityImpl(port) {
        // The production bridge needs a Vert.x context a plain unit test has none of.
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private class FixedScan(private val snapshot: AuthzPolicySnapshot) : PolicyScanPort {
        var calls = 0
        override suspend fun scan(): AuthzPolicySnapshot {
            calls++
            return snapshot
        }
    }

    private class FailingScan(private val boom: Throwable) : PolicyScanPort {
        override suspend fun scan(): AuthzPolicySnapshot = throw boom
    }

    @Test
    fun `every raw signal the scan found survives the activity unchanged`() {
        val comparison = PrincipalTypeComparison(
            file = "openbank-infra/opa/policies/rest.rego",
            line = 42,
            literalValue = "SERVICE",
            snippet = "input.principal.type == \"SERVICE\"",
        )
        val snapshot = AuthzPolicySnapshot(
            regoFilesScanned = 7,
            emittedPrincipalTypes = setOf("HUMAN", "AI_AGENT", "ANONYMOUS"),
            principalTypeComparisons = listOf(comparison),
            unwrappedAgentIdComparisons = emptyList(),
            toolTiersVocabulary = setOf("read", "write_proposal"),
            charterAllowTokens = emptyList(),
            charterDenyPatterns = emptyList(),
            restBypassReferences = emptyList(),
        )
        val port = FixedScan(snapshot)

        val collected = SyncCollect(port).collect()

        assertThat(collected).isEqualTo(snapshot)
        assertThat(collected.principalTypeComparisons).containsExactly(comparison)
        assertThat(collected.regoFilesScanned).isEqualTo(7)
        // One repo-checkout pass per run, not one per check.
        assertThat(port.calls).isEqualTo(1)
    }

    @Test
    fun `a scan failure fails the activity rather than yielding an empty snapshot`() {
        assertThatThrownBy { SyncCollect(FailingScan(IOException("repo root unreadable"))).collect() }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("repo root unreadable")
    }
}
