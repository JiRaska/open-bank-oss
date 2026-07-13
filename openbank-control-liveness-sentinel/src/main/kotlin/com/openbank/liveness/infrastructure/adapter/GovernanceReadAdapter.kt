// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.liveness.application.port.out.GovernanceReadPort
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Reads the ADR-0160 advisory allowlists straight from `openbank-libs/governance/rules.yaml`
 * (mounted read-only, same pattern as `read.governance` for every other control-plane agent).
 *
 * Full YAML-path wiring against the deployed rules.yaml ConfigMap is tracked separately; this
 * stub returns an empty allowlist (fail-open on classification, not on the underlying finding —
 * an un-allowlisted item is still surfaced, just without the "already tracked" distinction), to
 * keep the workflow structurally complete, matching the finops-agent/devops-agent bootstrap
 * pattern.
 */
@ApplicationScoped
class GovernanceReadAdapter : GovernanceReadPort {

    private val log = Logger.getLogger(GovernanceReadAdapter::class.java)

    override suspend fun eventConsumerAllowlist(): Set<String> {
        log.debug("Reading rules.yaml event-consumer-liveness allowlist — stub")
        // TODO(ADR-0163): parse rules.yaml `blocked_on` / allowlist entries for mechanism 1
        return emptySet()
    }

    override suspend fun lineageAllowlist(): Set<String> {
        log.debug("Reading rules.yaml lineage-vs-code allowlist — stub")
        // TODO(ADR-0163): parse rules.yaml `blocked_on` / allowlist entries for mechanism 2
        return emptySet()
    }
}
