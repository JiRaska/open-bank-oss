// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * The produced Clock must be UTC: proposal timestamps and the runs-per-day charter window are
 * both defined in UTC, so a system-default-zone clock would shift the day boundary per pod.
 */
class ClockProducerTest {

    @Test
    fun `the produced clock is anchored on UTC, not the JVM default zone`() {
        val clock = ClockProducer().clock()

        assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun `the clock advances - it is not a fixed one`() {
        val clock = ClockProducer().clock()

        val first = clock.instant()
        Thread.sleep(2)
        assertThat(clock.instant()).isAfterOrEqualTo(first)
    }
}
