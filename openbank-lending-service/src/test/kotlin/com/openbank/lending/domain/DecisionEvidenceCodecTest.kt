// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain

import com.openbank.lending.domain.model.DecisionEvidenceCodec
import com.openbank.lending.domain.model.DecisionReasonView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The codec must read back exactly what `OriginationDecisionService` writes:
 * reasons as `CODE:ruleId` with `-` for "no rule", versions as `KIND=n`, both comma-joined.
 */
class DecisionEvidenceCodecTest {

    @Test
    fun `decodes the reason csv the engine writes, mapping the dash to no rule`() {
        val reasons = DecisionEvidenceCodec.reasons("AFFORDABILITY_FAILED:starter-af-dsti,INPUT_MISSING:-")
        assertThat(reasons).containsExactly(
            DecisionReasonView("AFFORDABILITY_FAILED", "starter-af-dsti"),
            DecisionReasonView("INPUT_MISSING", null),
        )
    }

    @Test
    fun `decodes pinned policy versions per table kind`() {
        assertThat(DecisionEvidenceCodec.policyVersions("EXCLUSION=1,ELIGIBILITY=2,AFFORDABILITY=1,PRICING_BAND=3"))
            .containsExactlyInAnyOrderEntriesOf(
                mapOf("EXCLUSION" to 1, "ELIGIBILITY" to 2, "AFFORDABILITY" to 1, "PRICING_BAND" to 3),
            )
    }

    @Test
    fun `is total - blanks and malformed fragments never throw`() {
        assertThat(DecisionEvidenceCodec.reasons(null)).isEmpty()
        assertThat(DecisionEvidenceCodec.reasons("")).isEmpty()
        assertThat(DecisionEvidenceCodec.reasons("NOCOLON")).containsExactly(DecisionReasonView("NOCOLON", null))
        assertThat(DecisionEvidenceCodec.policyVersions("ELIGIBILITY=x,=1,PRICING_BAND=2")).containsExactly(
            java.util.AbstractMap.SimpleEntry("PRICING_BAND", 2),
        )
        assertThat(DecisionEvidenceCodec.matchedRuleIds(" a , ,b ")).containsExactly("a", "b")
    }
}
