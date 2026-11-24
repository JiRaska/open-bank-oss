// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.config

import com.openbank.mcp.application.McpAccountRefs
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Supplies the HMAC key behind [McpAccountRefs] (#16678) from `openbank.mcp.account-ref.secret`.
 *
 * Two modes, decided once at boot (`@Startup`, since an `@ApplicationScoped` bean is lazy):
 *  - **configured** (>= 32 bytes): shared by every replica, so a ref survives restarts and crosses pods;
 *  - **absent**: a per-process random key, one WARN (never the value), and the gauge
 *    `openbank.mcp.account_ref.ephemeral_key` = 1. A ref then does not survive a restart or reach
 *    another pod; such a ref is REFUSED (fails closed), and the model re-reads `list_accounts`.
 *    Absent is tolerated so an unseeded secret does not take down an existing channel.
 *
 * A value that IS configured but shorter than 32 bytes is a misconfiguration and refuses boot.
 */
@Startup
@ApplicationScoped
class AccountRefKeyConfig(
    @ConfigProperty(name = "openbank.mcp.account-ref.secret") secret: Optional<String>,
    registry: MeterRegistry,
) {
    private val resolved: Resolved = fromSecret(secret) { op ->
        registry.counter(UNAVAILABLE_COUNTER, "reason", "no_session", "op", op).increment()
    }

    init {
        if (resolved.ephemeral) {
            log.warn(
                "openbank.mcp.account-ref.secret is not set: using a per-process random account-ref key; " +
                    "accountRef values will not survive a restart or cross replicas (they are refused)",
            )
        }
        Gauge.builder(EPHEMERAL_GAUGE) { if (resolved.ephemeral) 1.0 else 0.0 }
            .description("1 while account refs use a per-process random key (secret not configured)")
            .register(registry)
    }

    @Produces
    @ApplicationScoped
    fun accountRefs(): McpAccountRefs = resolved.refs

    class Resolved(val refs: McpAccountRefs, val ephemeral: Boolean)

    companion object {
        const val EPHEMERAL_GAUGE = "openbank.mcp.account_ref.ephemeral_key"

        /** `reason="no_session"`, `op="issue"|"resolve"`: a handle withheld or refused for want of a `sid`. */
        const val UNAVAILABLE_COUNTER = "openbank.mcp.account_ref.unavailable"
        private val log = Logger.getLogger(AccountRefKeyConfig::class.java)

        fun fromSecret(secret: Optional<String>, onUnavailable: (String) -> Unit = {}): Resolved {
            if (secret.isEmpty) return Resolved(McpAccountRefs.ephemeral(onUnavailable), ephemeral = true)
            val bytes = secret.get().toByteArray(Charsets.UTF_8)
            check(bytes.size >= McpAccountRefs.MIN_KEY_BYTES) {
                "openbank.mcp.account-ref.secret (env OPENBANK_MCP_ACCOUNT_REF_SECRET) is set but shorter than " +
                    "${McpAccountRefs.MIN_KEY_BYTES} bytes; refusing to start with a weak account-ref key"
            }
            return Resolved(McpAccountRefs(bytes, onUnavailable), ephemeral = false)
        }
    }
}
