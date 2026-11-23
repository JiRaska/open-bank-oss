// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.application

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Per-host politeness bound (ADR-0387 D6): at most one request per host per [minInterval]. */
class HostRateLimiter(private val minInterval: Duration, private val clock: Clock = Clock.systemUTC()) {
    private val last = ConcurrentHashMap<String, Long>()

    fun tryAcquire(host: String): Boolean {
        val now = clock.millis()
        var granted = false
        last.compute(host) { _, prev ->
            if (prev == null || now - prev >= minInterval.toMillis()) {
                granted = true
                now
            } else {
                prev
            }
        }
        return granted
    }
}
