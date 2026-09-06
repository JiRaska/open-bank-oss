// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * The produced clock must be UTC. The scheduled workflow id truncates this clock to a day, so a
 * JVM-default zone would split the dedupe window on some days and merge it on others depending on
 * where the pod happens to run.
 */
class ClockProducerTest {

    @Test
    fun `the produced clock is on UTC, not the JVM default zone`() {
        assertThat(ClockProducer().clock().zone).isEqualTo(ZoneOffset.UTC)
    }
}
