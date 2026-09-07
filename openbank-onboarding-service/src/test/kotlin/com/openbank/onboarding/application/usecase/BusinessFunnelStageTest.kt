// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.onboarding.domain.model.BusinessCaseStage
import com.openbank.onboarding.domain.model.BusinessFunnelStage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BusinessFunnelStageTest {

    @Test
    fun `every case status has a board column`() {
        // The mapping is a `when` over the enum, so this cannot fail while the code compiles —
        // which is the point: the test exists so that ADDING a status without classifying it is
        // a compile error rather than a silent extra column, and this asserts the count nobody
        // else counts.
        val columns = BusinessCaseStage.entries.map { BusinessFunnelStage.of(it) }
        assertThat(columns).hasSize(BusinessCaseStage.entries.size)
        assertThat(columns).doesNotContainNull()
    }

    @Test
    fun `only the manual review status is work for the bank`() {
        val needingReview = BusinessCaseStage.entries.filter {
            BusinessFunnelStage.of(it) == BusinessFunnelStage.NEEDS_REVIEW
        }
        assertThat(needingReview).containsExactly(BusinessCaseStage.MANUAL_REVIEW)
    }

    @Test
    fun `both terminal non-customer statuses collapse into one closed column`() {
        assertThat(BusinessFunnelStage.of(BusinessCaseStage.REJECTED)).isEqualTo(BusinessFunnelStage.CLOSED)
        assertThat(BusinessFunnelStage.of(BusinessCaseStage.ABANDONED)).isEqualTo(BusinessFunnelStage.CLOSED)
    }

    @Test
    fun `an unknown status is not guessed`() {
        // A status this build does not model must be dropped by the consumer, never mapped onto
        // a neighbouring one — a guessed board column looks exactly like a measurement.
        assertThat(BusinessCaseStage.from("SIGNED_BY_NOTARY")).isNull()
        assertThat(BusinessCaseStage.from(null)).isNull()
        assertThat(BusinessCaseStage.from("signed")).isNull()
        assertThat(BusinessCaseStage.from("SIGNED")).isEqualTo(BusinessCaseStage.SIGNED)
    }
}
