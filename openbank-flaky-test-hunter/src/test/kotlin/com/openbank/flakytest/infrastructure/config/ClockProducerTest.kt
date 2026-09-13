// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class ClockProducerTest {

    /**
     * The produced clock must be UTC, not the JVM default zone: every workflow id in this service is
     * a UTC day stamp, so a system-default clock would make the daily dedupe depend on where the pod
     * runs (the same defect [com.openbank.flakytest.application.usecase.FlakyTestHunterService] is
     * tested against for its own truncation).
     */
    @Test
    fun `the produced clock is UTC`() {
        assertThat(ClockProducer().clock().zone).isEqualTo(ZoneOffset.UTC)
    }
}
