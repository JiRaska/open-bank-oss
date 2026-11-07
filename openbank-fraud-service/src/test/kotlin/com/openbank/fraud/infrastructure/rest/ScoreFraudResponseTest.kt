// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.rest

import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.FraudVerdict
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScoreFraudResponseTest {

    private fun score(verdict: FraudVerdict) = FraudScore(verdict, 10, listOf("r"), "v1")

    @Test
    fun `every produced verdict goes on the wire under its own name`() {
        listOf(FraudVerdict.ALLOW, FraudVerdict.CHALLENGE, FraudVerdict.REVIEW, FraudVerdict.DECLINE).forEach {
            assertThat(score(it).toResponse().verdict).isEqualTo(it.name)
        }
    }

    @Test
    fun `the consumer-only UNKNOWN verdict is never emitted`() {
        assertThatThrownBy { score(FraudVerdict.UNKNOWN).toResponse() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("UNKNOWN")
    }
}
