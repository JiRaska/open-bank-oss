// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

/**
 * Configuration for the case-coordinator agent (ADR-0244).
 */
@ConfigMapping(prefix = "openbank.case-coordinator")
@ApplicationScoped
interface CaseCoordinatorConfig {

    @WithDefault("0 0 5 * * ?")
    fun sweepCron(): String

    @WithDefault("https://api.deepinfra.com/v1/openai")
    fun modelEndpoint(): String

    @WithDefault("deepseek-ai/DeepSeek-V3.2")
    fun modelId(): String
}
