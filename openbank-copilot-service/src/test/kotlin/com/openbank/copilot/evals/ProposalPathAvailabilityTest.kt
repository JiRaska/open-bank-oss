// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.evals

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stops an [ScenarioOutcome.UNAVAILABLE] declaration outliving the gap it describes.
 *
 * The two unavailable scenarios in [COPILOT_PROPOSAL_SCENARIOS] each rest on a **checkable fact**
 * about this service's source tree, not on an intention. This class re-proves that fact on every
 * run and goes RED when it stops holding — which is the signal to promote the scenario from
 * "declared unavailable" to a real assertion.
 *
 * The failure mode this exists to prevent is the one `evals/README.md` and
 * `openbank-libs/governance/evals/recordings/backlog.yaml` both name: an exclusion list that reads
 * as passing when it grows, and never as unchecked. There the rule is bidirectional (an undeclared
 * gap and a stale declaration are both errors); here the undeclared direction is covered by the
 * scenarios themselves and the stale direction is covered below.
 */
class ProposalPathAvailabilityTest {

    private val mainSources: List<File> =
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the pack can actually see this service's sources`() {
        // Control: without this, every assertion below would pass vacuously if the working directory
        // or the layout ever moved — a probe that reports clean because it read nothing.
        assertThat(mainSources)
            .withFailMessage("found no .kt files under src/main/kotlin — this guard is reading nothing")
            .isNotEmpty()
        assertThat(mainSources.map { it.name })
            .contains("ProposalToken.kt", "ActionConfirmResource.kt", "PaymentProposalTool.kt")
    }

    @Test
    fun `SCA-binding stays unavailable only while no production code issues a ProposalToken`() {
        val issuers = mainSources.filter { it.readText().contains("ProposalToken(") }

        assertThat(issuers)
            .withFailMessage(
                "PROMOTE THE SCENARIO: %s now constructs a ProposalToken, so the ADR-0089 D2 Track A " +
                    "SCA binding finally has a producer and " +
                    "'sca-binding-proposal-token-is-issued-and-owner-bound' must stop being declared " +
                    "UNAVAILABLE in CopilotProposalScenarios.kt — assert the binding (owner, TTL, " +
                    "one-time use) for real. Leaving the declaration in place would be exactly the " +
                    "stale-exclusion defect this guard exists to catch.",
                issuers.map { it.name },
            )
            // Only the declaration file itself may name the type.
            .allMatch { it.name == "ProposalToken.kt" }

        // And the declared scenario must still be present and still unavailable — the guard is
        // worthless if someone deletes the scenario instead of promoting it.
        val declared = COPILOT_PROPOSAL_SCENARIOS.single {
            it.id ==
                "sca-binding-proposal-token-is-issued-and-owner-bound"
        }
        assertThat(declared.subject).isInstanceOf(ProposalSubject.NotWiredYet::class.java)
    }

    @Test
    fun `consent-scope stays unavailable only while this service has no consent-scope check`() {
        val consentAware = mainSources.filter {
            val text = it.readText()
            text.contains("consentScope", ignoreCase = true) || text.contains("consent_scope", ignoreCase = true)
        }

        assertThat(consentAware)
            .withFailMessage(
                "PROMOTE THE SCENARIO: %s now references a consent scope, so " +
                    "'proposal-never-exceeds-psd2-consent-scope' must stop being declared UNAVAILABLE " +
                    "and assert the ADR-0195 check for real (issue #2414).",
                consentAware.map { it.name },
            )
            .isEmpty()

        val declared = COPILOT_PROPOSAL_SCENARIOS.single { it.id == "proposal-never-exceeds-psd2-consent-scope" }
        assertThat(declared.subject).isInstanceOf(ProposalSubject.NotWiredYet::class.java)
    }
}
