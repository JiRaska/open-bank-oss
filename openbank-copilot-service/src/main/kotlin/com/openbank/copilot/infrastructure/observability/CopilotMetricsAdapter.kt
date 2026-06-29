// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for the copilot chat surface (C8 prod-readiness sweep / ADR-0077).
 * Exposes counters that ops can alert on — a chat request is money-path adjacent (proposals
 * may follow) so observability here is safety-relevant, not just informational.
 *
 *  - `openbank_copilot_chat_requests_total{service="copilot",outcome}` — completed turns by
 *    outcome: `replied`, `disabled`, `injection_blocked`.
 *
 * Counters are created lazily on first call (Micrometer deduplicates by name+tags), so no
 * `@PostConstruct` registration is required.
 *
 * Service-local [MeterRegistry] (null-safe via [Instance]): copilot-specific meters must not
 * force a fleet-wide rebuild (service-local metrics pattern, ADR-0085 §2). The `Instance`
 * constructor guard makes the bean safe in slim test slices where Prometheus is absent.
 */
@ApplicationScoped
class CopilotMetricsAdapter(private val registry: MeterRegistry?) {

    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    /** Increment the request counter for the given [outcome] tag value. */
    fun recordChatRequest(outcome: String) {
        registry?.let {
            Counter.builder(METRIC_CHAT_REQUESTS)
                .tag("service", SERVICE)
                .tag("outcome", outcome)
                .description("Copilot chat turns completed, tagged by outcome")
                .register(it)
                .increment()
        }
    }

    companion object {
        private const val SERVICE = "copilot"
        const val OUTCOME_REPLIED = "replied"
        const val OUTCOME_DISABLED = "disabled"
        const val OUTCOME_INJECTION_BLOCKED = "injection_blocked"
        private const val METRIC_CHAT_REQUESTS = "openbank.copilot.chat.requests"
    }
}
