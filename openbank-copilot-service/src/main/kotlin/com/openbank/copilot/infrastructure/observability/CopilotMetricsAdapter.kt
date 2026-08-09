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
 *  - `openbank_copilot_erasure_identity_total{service="copilot",source}` — how each conversation
 *    row's GDPR Art. 17 erasure identity was resolved at WRITE time: `claim` (the token carried a
 *    `party_id`) or `absent` (it did not, so the row is reachable only by its OIDC `sub`).
 *  - `openbank_copilot_party_erasure_total{service="copilot",outcome}` — `PARTY_ERASED` events by
 *    what the delete actually did: `erased` (>=1 row) or `no_match` (0 rows).
 *
 * The last two exist because a count in a log line is not a control (#4175). `no_match` is a
 * legitimate outcome — a party that never chatted holds nothing — so it is not on its own an
 * alert; what is alertable is `no_match` staying at 100% of `party_erasure_total` while
 * `erasure_identity_total{source="absent"}` is non-zero, which is the signature of erasure
 * requests arriving for people whose rows were written without a resolvable party id.
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

    /**
     * Record how the erasure identity was resolved for one written turn. [source] is
     * [SOURCE_CLAIM] or [SOURCE_ABSENT].
     */
    fun recordErasureIdentity(source: String) {
        registry?.let {
            Counter.builder(METRIC_ERASURE_IDENTITY)
                .tag("service", SERVICE)
                .tag("source", source)
                .description("Copilot conversation writes by how the GDPR erasure identity was resolved")
                .register(it)
                .increment()
        }
    }

    /**
     * Record the result of one `PARTY_ERASED` delete. [outcome] is [OUTCOME_ERASED] or
     * [OUTCOME_NO_MATCH] — the distinction a log line carrying only the count cannot express.
     */
    fun recordPartyErasure(outcome: String) {
        registry?.let {
            Counter.builder(METRIC_PARTY_ERASURE)
                .tag("service", SERVICE)
                .tag("outcome", outcome)
                .description("PARTY_ERASED events processed, tagged by whether any row was removed")
                .register(it)
                .increment()
        }
    }

    companion object {
        private const val SERVICE = "copilot"
        const val OUTCOME_REPLIED = "replied"
        const val OUTCOME_DISABLED = "disabled"
        const val OUTCOME_INJECTION_BLOCKED = "injection_blocked"

        /** The bearer carried a usable `party_id` claim. */
        const val SOURCE_CLAIM = "claim"

        /** The bearer carried no usable `party_id`; the row is reachable only by its OIDC `sub`. */
        const val SOURCE_ABSENT = "absent"

        /** The erasure delete removed at least one conversation. */
        const val OUTCOME_ERASED = "erased"

        /** The erasure delete removed nothing. */
        const val OUTCOME_NO_MATCH = "no_match"

        private const val METRIC_CHAT_REQUESTS = "openbank.copilot.chat.requests"
        private const val METRIC_ERASURE_IDENTITY = "openbank.copilot.erasure.identity"
        private const val METRIC_PARTY_ERASURE = "openbank.copilot.party.erasure"
    }
}
