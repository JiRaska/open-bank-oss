// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.DeliveryTransition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0239 D4. This is where the consumer's correctness lives: the consumer itself needs a broker,
 * the rule does not, and every property that makes at-least-once delivery survivable is here.
 */
class DeliveryTransitionTest {

    @Test
    fun `a first terminal outcome settles a pending send`() {
        assertThat(DeliveryTransition.next(DeliveryStatus.PENDING, "SENT")).isEqualTo(DeliveryStatus.CONFIRMED)
        assertThat(DeliveryTransition.next(DeliveryStatus.PENDING, "SUPPRESSED")).isEqualTo(DeliveryStatus.SUPPRESSED)
        assertThat(DeliveryTransition.next(DeliveryStatus.PENDING, "FAILED")).isEqualTo(DeliveryStatus.FAILED)
    }

    /**
     * The property that makes an at-least-once topic safe. The outcomes topic is partitioned by
     * notification id, not by correlation id, so two outcomes for one send have NO guaranteed order
     * — a last-write-wins consumer would let whichever record happened to arrive second win.
     */
    @Test
    fun `a redelivered or later outcome cannot overwrite a settled send`() {
        assertThat(DeliveryTransition.next(DeliveryStatus.CONFIRMED, "SENT")).isNull()
        assertThat(DeliveryTransition.next(DeliveryStatus.SUPPRESSED, "SENT")).isNull()
        assertThat(DeliveryTransition.next(DeliveryStatus.SUPPRESSED, "FAILED")).isNull()
        assertThat(DeliveryTransition.next(DeliveryStatus.FAILED, "SENT")).isNull()
        assertThat(DeliveryTransition.next(DeliveryStatus.FAILED, "SUPPRESSED")).isNull()
    }

    /**
     * The single exception, and the reason the rule is "first terminal wins" rather than "terminal
     * is final": an SMTP accept is genuinely true when written and genuinely stops being true when
     * the message bounces afterwards.
     */
    @Test
    fun `a bounce refines an earlier confirmation and nothing else`() {
        assertThat(DeliveryTransition.next(DeliveryStatus.CONFIRMED, "BOUNCED")).isEqualTo(DeliveryStatus.FAILED)
        assertThat(DeliveryTransition.next(DeliveryStatus.PENDING, "BOUNCED")).isEqualTo(DeliveryStatus.FAILED)
        // A bounce cannot resurrect a send the consent gate refused — nothing was ever sent to bounce.
        assertThat(DeliveryTransition.next(DeliveryStatus.SUPPRESSED, "BOUNCED")).isNull()
        assertThat(DeliveryTransition.next(DeliveryStatus.FAILED, "BOUNCED")).isNull()
    }

    /**
     * The outcomes contract is additive: a future notification-service may emit a value this build
     * has never heard of. Ignoring it is the only safe reading — treating it as an error would wedge
     * the channel on a change that was declared backward-compatible.
     */
    @Test
    fun `an unknown outcome value is ignored, never an error`() {
        assertThat(DeliveryTransition.next(DeliveryStatus.PENDING, "DEFERRED")).isNull()
        assertThat(DeliveryTransition.next(DeliveryStatus.PENDING, "")).isNull()
        assertThat(DeliveryTransition.next(DeliveryStatus.PENDING, "sent")).isNull()
    }
}
