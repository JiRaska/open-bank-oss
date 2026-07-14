// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import com.openbank.govaudit.application.port.out.GovernanceRulesPort
import com.openbank.govaudit.infrastructure.config.GovernanceAuditorConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Reads `openbank-libs/governance/rules.yaml`'s `review` and `money_path_services` sections.
 *
 * Live parsing of the mounted `rules.yaml` (same pattern as `read.governance` for every other
 * control-plane agent) is tracked separately; this returns config-defaulted values that mirror
 * the CURRENT rules.yaml content at the time this service was scaffolded (`review.default_approvals:
 * 1`, `review.money_path_approvals: 2`, and the `money_path_services` list) so the workflow is
 * structurally complete and correct on day one, matching the finops-agent/devops-agent bootstrap
 * pattern. A future rules.yaml edit will NOT be picked up until the live-parsing follow-up lands —
 * that drift risk is the explicit trade-off, not a silent one.
 */
@ApplicationScoped
class GovernanceRulesReadAdapter(private val config: GovernanceAuditorConfig) : GovernanceRulesPort {

    private val log = Logger.getLogger(GovernanceRulesReadAdapter::class.java)

    override suspend fun moneyPathServices(): Set<String> {
        // TODO(ADR-0164): parse rules.yaml `money_path_services` live instead of this config mirror.
        return config.moneyPathServices().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    override suspend fun defaultApprovals(): Int {
        log.debugf("Reading rules.yaml review.default_approvals — config mirror")
        return config.defaultApprovals()
    }

    override suspend fun moneyPathApprovals(): Int {
        log.debugf("Reading rules.yaml review.money_path_approvals — config mirror")
        return config.moneyPathApprovals()
    }
}
