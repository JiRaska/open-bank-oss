// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import java.util.Optional

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

    /**
     * Base URL of the OpenAI-compatible model backend (LiteLLM gateway, ADR-0174). The API key is
     * read separately via an OPTIONAL lookup (governance-auditor.model.api-key) so an un-seeded key
     * degrades the diagnosis call instead of CrashLooping the pod at boot
     * (SmallRye SRCFG00040 on empty bind).
     */
    @WithDefault("http://litellm.ai-platform:4000")
    fun llmGatewayUrl(): String

    /** Upstream model name, sent verbatim — the same DeepSeek model sibling ops agents run. */
    @WithDefault("deepseek-ai/DeepSeek-V3.2")
    fun modelId(): String

    /**
     * Live rules.yaml source, ADR-0164 (GovernanceRulesReadAdapter): a document mounted at this
     * path wins over [rulesYamlUrl]; when neither source is reachable the config mirror below is
     * used and the divergence is logged. Empty/absent disables the file source — an empty String
     * is NOT an option here: SmallRye answers SRCFG00040 on an empty-string bind.
     */
    fun rulesYamlPath(): Optional<String>

    /** Raw rules.yaml URL (e.g. raw.githubusercontent.com on main). Empty/absent disables HTTP. */
    @WithDefault("https://raw.githubusercontent.com/JiRaska/open-bank-oss/main/openbank-libs/governance/rules.yaml")
    fun rulesYamlUrl(): Optional<String>

    // Fallback mirror of rules.yaml `review.default_approvals` / `review.money_path_approvals`,
    // used only when neither live source above is reachable (GovernanceRulesReadAdapter logs the
    // fallback and any divergence at WARN).
    @WithDefault("1")
    fun defaultApprovals(): Int

    @WithDefault("2")
    fun moneyPathApprovals(): Int

    // Fallback mirror of rules.yaml `money_path_services`, `openbank-` prefix included,
    // comma-separated — same fallback-and-divergence contract as the approvals above.
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
