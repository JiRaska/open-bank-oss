// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.kyc.application.port.out.AdverseMediaOutcome
import com.openbank.kyc.domain.model.CheckStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The default adapter must report absence of a source — never an absence of findings. */
class UnconfiguredAdverseMediaSourceTest {

    private val source = UnconfiguredAdverseMediaSource()

    @Test
    fun `reports no source id`() {
        assertThat(source.sourceId).isNull()
    }

    @Test
    fun `screening a party without a configured source is SOURCE_NOT_CONFIGURED, not NO_HIT`(): Unit = runBlocking {
        val result = source.screen("Adverse Subject Zero", "kyc-adverse-media-1")

        assertThat(result.outcome).isEqualTo(AdverseMediaOutcome.SOURCE_NOT_CONFIGURED)
        assertThat(result.outcome).isNotEqualTo(AdverseMediaOutcome.NO_HIT)
        assertThat(result.outcome.isResolved).isFalse()
        assertThat(result.sourceId).isNull()
    }

    @Test
    fun `the unconfigured outcome never resolves a check to PASSED`(): Unit = runBlocking {
        // Every name, adversely-reported or not, lands in the same unresolved state — the honest
        // answer when nothing was read. Subjects are fictional by construction: this is a PUBLIC
        // repository and adverse-media screening is negative-news search, so a real person's name
        // must never appear as a test subject here.
        listOf("Adverse Subject Zero", "Jan Novák", "").forEach { name ->
            assertThat(source.screen(name, "k").outcome.toCheckStatus()).isEqualTo(CheckStatus.MANUAL_REVIEW)
        }
    }
}
