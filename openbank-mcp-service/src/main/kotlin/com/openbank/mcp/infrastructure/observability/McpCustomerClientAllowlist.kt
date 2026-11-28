// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.observability

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * The bank-registered customer MCP clients whose short ids may appear as the `client` metric tag
 * (ADR-0384 D1). `client` is a safe label ONLY because ADR-0383 D1 admits allowlisted clients with
 * dynamic registration off, so the value set is this list; anything else is [UNLISTED].
 *
 * Capped at [MAX_ENTRIES]: above that `client` stops being a safe Prometheus dimension and
 * per-client views must move to the warehouse, which needs a superseding decision rather than a
 * config edit. `@Startup` because the cap is a boot-time gate — an `@ApplicationScoped` bean is
 * lazy, and a cap that only throws on the first customer call would let an over-long list deploy
 * green.
 */
@Startup
@ApplicationScoped
class McpCustomerClientAllowlist(entries: List<String>) {

    @Inject
    constructor(
        @ConfigProperty(name = "mcp.customer.client-allowlist") configured: Optional<List<String>>,
    ) : this(configured.orElse(emptyList()))

    val clients: Set<String> = entries.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    init {
        check(clients.size <= MAX_ENTRIES) {
            "mcp.customer.client-allowlist has ${clients.size} entries; ADR-0384 D1 caps it at $MAX_ENTRIES " +
                "because `client` is a metric label. A larger allowlist needs a superseding decision."
        }
        clients.forEach {
            require(SHORT_ID.matches(it)) { "allowlist entry '$it' is not a short id ([a-z0-9-]{1,32})" }
        }
    }

    /** The bounded tag value for a raw client id: the allowlist entry itself, or [UNLISTED]. */
    fun label(rawClientId: String?): String = rawClientId?.takeIf { it in clients } ?: UNLISTED

    companion object {
        const val MAX_ENTRIES = 25
        const val UNLISTED = "unlisted"

        /** Placeholder carried by agent and operator series, so every channel has the same tag keys. */
        const val NONE = "none"
        private val SHORT_ID = Regex("[a-z0-9-]{1,32}")
    }
}
