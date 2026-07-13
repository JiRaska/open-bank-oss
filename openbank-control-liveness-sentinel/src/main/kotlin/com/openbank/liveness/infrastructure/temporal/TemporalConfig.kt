// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.temporal

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.temporal")
@ApplicationScoped
interface TemporalConfig {
    @WithDefault("false")
    fun enabled(): Boolean

    @WithDefault("localhost:7233")
    fun serverUrl(): String

    @WithDefault("openbank")
    fun namespace(): String

    @WithDefault("control-liveness-sentinel")
    fun taskQueue(): String
}
