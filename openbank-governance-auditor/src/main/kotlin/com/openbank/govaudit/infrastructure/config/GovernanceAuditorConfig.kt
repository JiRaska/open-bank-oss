// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.governance-auditor")
@ApplicationScoped
interface GovernanceAuditorConfig {
    /**
     * Cron for the periodic sweep (ADR-0164).
     *
     * Declared here because it lives under this mapping's prefix: SmallRye validates a
     * `@ConfigMapping` prefix as a CLOSED set, so a key added to `application.yaml` with no
     * matching accessor fails the whole application at boot with `ConfigValidationException` —
     * not a warning about one property, a dead service.
     */
    @WithDefault("0 30 4 * * ?")
    fun auditCron(): String

    @WithDefault("https://api.github.com")
    fun githubApiUrl(): String

    @WithDefault("JiRaska/open-bank-oss")
    fun githubRepo(): String

    @WithDefault("http://litellm.ai-platform:4000")
    fun llmGatewayUrl(): String

    // Mirrors rules.yaml `review.default_approvals` / `review.money_path_approvals` until the
    // live-parsing follow-up lands (GovernanceRulesReadAdapter).
    @WithDefault("1")
    fun defaultApprovals(): Int

    @WithDefault("2")
    fun moneyPathApprovals(): Int

    // Mirrors rules.yaml `money_path_services`, `openbank-` prefix included, comma-separated.
    @WithDefault(
        "openbank-ledger-service,openbank-transaction-service,openbank-account-service," +
            "openbank-balance-service,openbank-sepa-payment,openbank-sepa-instant," +
            "openbank-domestic-payment,openbank-clearing-service,openbank-swift-service," +
            "openbank-fx-service,openbank-lending-service,openbank-sca-service," +
            "openbank-consent-service,openbank-fraud-service,openbank-billing-service," +
            "openbank-settlement-service,openbank-sanctions-service",
    )
    fun moneyPathServices(): String
}
