// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * The produced clock's ZONE is load-bearing: `ReleaseStewardService.scheduledWorkflowId` formats
 * the UTC day into the dedupe id, so a system-default-zone clock would move the day boundary with
 * the pod's timezone and let two sweeps run on the same UTC day.
 */
class ClockProducerTest {

    @Test
    fun `the produced clock is on UTC, not the JVM default zone`() {
        assertThat(ClockProducer().clock().zone).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun `the produced clock is a live clock, not a fixed one`() {
        val clock = ClockProducer().clock()
        val first = clock.instant()
        Thread.sleep(5)

        assertThat(clock.instant()).isAfter(first)
    }
}
