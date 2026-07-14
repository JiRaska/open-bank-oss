// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.liveness-sentinel")
@ApplicationScoped
interface LivenessSentinelConfig {
    @WithDefault("http://prometheus-operated.observability:9090")
    fun prometheusUrl(): String

    @WithDefault("http://alertmanager-operated.observability:9093")
    fun alertmanagerUrl(): String

    @WithDefault("http://litellm.ai-platform:4000")
    fun llmGatewayUrl(): String

    // Matches ADR-0160 mechanism 3's own Alertmanager paging threshold (2x expected interval) —
    // this agent's CRITICAL finding and the underlying page can never silently disagree.
    @WithDefault("2.0")
    fun staleHeartbeatMultiplier(): Double

    // Fires a WARNING before the heartbeat reaches the paging threshold, so a control that is
    // merely getting stale is visible before it becomes a page (ADR-0163's own worked example).
    @WithDefault("1.5")
    fun warnHeartbeatMultiplier(): Double

    @WithDefault("3")
    fun consecutiveDriftThreshold(): Int
}
