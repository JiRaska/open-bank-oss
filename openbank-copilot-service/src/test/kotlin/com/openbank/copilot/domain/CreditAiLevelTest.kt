// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0269 rule 5: the three levels and their containment. */
class CreditAiLevelTest {

    @Test
    fun `no consent is the explainer, not a refusal`() {
        assertThat(CreditAiLevel.from(profileUseConsent = false, agentConsent = false))
            .isEqualTo(CreditAiLevel.L0_EXPLAINER)
    }

    @Test
    fun `profile-use consent alone is the advisor`() {
        assertThat(CreditAiLevel.from(profileUseConsent = true, agentConsent = false))
            .isEqualTo(CreditAiLevel.L1_ADVISOR)
    }

    @Test
    fun `agent consent is the agent even if profile-use was recorded separately`() {
        assertThat(CreditAiLevel.from(profileUseConsent = false, agentConsent = true))
            .isEqualTo(CreditAiLevel.L2_AGENT)
        assertThat(CreditAiLevel.from(profileUseConsent = true, agentConsent = true))
            .isEqualTo(CreditAiLevel.L2_AGENT)
    }

    @Test
    fun `the levels contain each other so a tool never has to test for equality`() {
        assertThat(CreditAiLevel.L2_AGENT.atLeast(CreditAiLevel.L1_ADVISOR)).isTrue()
        assertThat(CreditAiLevel.L2_AGENT.atLeast(CreditAiLevel.L0_EXPLAINER)).isTrue()
        assertThat(CreditAiLevel.L1_ADVISOR.atLeast(CreditAiLevel.L2_AGENT)).isFalse()
        // The property that matters: turning L2 on must not switch an L1 tool off.
        CreditAiLevel.entries.forEach { assertThat(it.atLeast(CreditAiLevel.L0_EXPLAINER)).isTrue() }
    }
}
