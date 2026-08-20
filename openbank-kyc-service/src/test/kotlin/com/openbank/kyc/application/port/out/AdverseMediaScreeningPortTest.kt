// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import com.openbank.kyc.domain.model.CheckStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The fail-closed contract, tested in **both directions**: a resolved clean screen is the only
 * thing that may PASS, and every unresolved outcome — including the one this platform is
 * permanently in today — must not be representable as clean.
 */
class AdverseMediaScreeningPortTest {

    @Test
    fun `only a reached, empty source passes the check`() {
        assertThat(AdverseMediaOutcome.NO_HIT.toCheckStatus()).isEqualTo(CheckStatus.PASSED)
    }

    @Test
    fun `a hit is manual review, never an automated failure`() {
        // ADR-0116 four-eyes / ADR-0256 D2: a trigger opens a case, it never decides one.
        assertThat(AdverseMediaOutcome.HIT.toCheckStatus()).isEqualTo(CheckStatus.MANUAL_REVIEW)
    }

    @Test
    fun `an unreachable source is unresolved, not clean`() {
        assertThat(AdverseMediaOutcome.SOURCE_UNAVAILABLE.toCheckStatus()).isEqualTo(CheckStatus.MANUAL_REVIEW)
        assertThat(AdverseMediaOutcome.SOURCE_UNAVAILABLE.isResolved).isFalse()
    }

    @Test
    fun `an unconfigured source is unresolved, not clean`() {
        assertThat(AdverseMediaOutcome.SOURCE_NOT_CONFIGURED.toCheckStatus()).isEqualTo(CheckStatus.MANUAL_REVIEW)
        assertThat(AdverseMediaOutcome.SOURCE_NOT_CONFIGURED.isResolved).isFalse()
    }

    /**
     * The invariant stated as a set, so that adding a future outcome member without deciding its
     * side of the line is caught here rather than by whatever first records a PASSED check.
     */
    @Test
    fun `NO_HIT is the only outcome in the whole enum that can pass`() {
        val passing = AdverseMediaOutcome.entries.filter { it.toCheckStatus() == CheckStatus.PASSED }
        assertThat(passing).containsExactly(AdverseMediaOutcome.NO_HIT)
    }

    @Test
    fun `a clean-looking result cannot be constructed without naming its source`() {
        assertThatThrownBy { AdverseMediaScreeningResult(AdverseMediaOutcome.NO_HIT, sourceId = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AdverseMediaScreeningResult(AdverseMediaOutcome.HIT, sourceId = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an unconfigured result cannot claim a source`() {
        assertThatThrownBy {
            AdverseMediaScreeningResult(AdverseMediaOutcome.SOURCE_NOT_CONFIGURED, sourceId = "some-vendor")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a resolved result records the source that produced it`() {
        val r = AdverseMediaScreeningResult(AdverseMediaOutcome.HIT, sourceId = "vendor-x", matchedHeadline = "h")
        assertThat(r.sourceId).isEqualTo("vendor-x")
        assertThat(r.outcome.isResolved).isTrue()
    }
}
