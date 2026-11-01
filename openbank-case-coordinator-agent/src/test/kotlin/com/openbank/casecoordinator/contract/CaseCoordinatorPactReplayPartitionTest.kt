// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.contract

import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the replay coverage of the committed pact (#13482). Both folder verification classes select
 * interactions by provider state, so an interaction with NO state is selected by neither and is
 * silently never verified. This reads the committed pact and the real `@PactFilter` annotations of
 * the two classes, and requires every interaction to carry a state and to be selected exactly once.
 */
class CaseCoordinatorPactReplayPartitionTest {

    private val pact: JsonNode = ObjectMapper().readTree(File("../pacts/$PACT_FILE"))
    private val interactions: List<JsonNode> = pact["interactions"].toList()

    private fun filterOf(type: Class<*>): String = type.getAnnotation(PactFilter::class.java).value.single()

    private val positiveFilter = filterOf(CaseCoordinatorPactProviderVerificationTest::class.java)
    private val negativeFilter = filterOf(CaseCoordinatorUnauthorizedProviderVerificationTest::class.java)

    private fun states(i: JsonNode): List<String> = i["providerStates"]?.map { it["name"].asText() } ?: emptyList()

    // pact-jvm matches a state against the filter with String.matches; an interaction is selected when any state matches.
    private fun selectedBy(i: JsonNode, filter: String) = states(i).any { it.matches(Regex(filter)) }

    @Test
    fun `the committed pact holds the expected interactions`() {
        assertThat(interactions.map { it["description"].asText() }).containsExactlyInAnyOrder(
            "fetch the swarm thread for one case",
            "list open cases for the swarm roster",
            "fetch the active kill-switch status",
            "fetch kill-switch status without presenting any identity",
        )
    }

    @Test
    fun `every interaction declares a provider state`() {
        interactions.forEach { assertThat(states(it)).describedAs(it["description"].asText()).isNotEmpty() }
    }

    @Test
    fun `every interaction is selected by exactly one verification class`() {
        interactions.forEach {
            val selections = listOf(positiveFilter, negativeFilter).count { f -> selectedBy(it, f) }
            assertThat(selections).describedAs(it["description"].asText()).isEqualTo(1)
        }
    }

    @Test
    fun `three positive interactions and the anonymous 401 are replayed`() {
        assertThat(interactions.count { selectedBy(it, positiveFilter) }).isEqualTo(3)
        assertThat(interactions.filter { selectedBy(it, negativeFilter) }.map { it["response"]["status"].asInt() })
            .containsExactly(401)
    }

    private companion object {
        const val PACT_FILE = "openbank-admin-ui-openbank-case-coordinator-agent.json"
    }
}
