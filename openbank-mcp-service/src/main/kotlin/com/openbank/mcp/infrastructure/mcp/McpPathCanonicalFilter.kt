// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.mcp

import io.quarkus.vertx.http.runtime.filters.Filters
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

/**
 * Refuses (400, fail closed) any request whose RAW path is not already canonical (review of #17004).
 *
 * The HTTP permissions (`bearer-only` / `customer-dpop`), the OIDC `tenant-paths` and the JAX-RS
 * resource match each see the path through their own decoding/normalisation, so a non-canonical
 * spelling — a matrix parameter (`/customer/mcp;a=b`), a percent-encoded character
 * (`/customer/%6dcp`), a dot segment (`/customer/./mcp`, `/x/../customer/mcp`), an empty segment —
 * can land on a resource under a permission written for a different spelling. Only one spelling is
 * served: anything else never reaches authentication or a resource. Registered at the highest filter
 * priority, so it runs before the security handlers on both listeners.
 */
@ApplicationScoped
class McpPathCanonicalFilter {

    private val log = Logger.getLogger(McpPathCanonicalFilter::class.java)

    fun register(@Observes filters: Filters) {
        filters.register(::filter, PRIORITY)
    }

    internal fun filter(ctx: RoutingContext) {
        val raw = ctx.request().path()
        if (raw == null || !isCanonical(raw)) {
            log.debug("refused non-canonical request path")
            ctx.response().setStatusCode(BAD_REQUEST).end()
        } else {
            ctx.next()
        }
    }

    companion object {
        private const val PRIORITY = Int.MAX_VALUE
        private const val BAD_REQUEST = 400

        /** True only for an absolute path with no `;`, `%`, `\`, empty, `.` or `..` segment. */
        fun isCanonical(path: String): Boolean {
            if (!path.startsWith("/")) return false
            if (path.any { it == ';' || it == '%' || it == '\\' }) return false
            if (path == "/") return true
            val segments = path.substring(1).split('/')
            // A single trailing slash is tolerated (`/mcp/`), an empty inner segment (`//`) is not.
            val inner = if (segments.last().isEmpty()) segments.dropLast(1) else segments
            return inner.none { it.isEmpty() || it == "." || it == ".." }
        }
    }
}
