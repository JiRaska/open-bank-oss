// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.render

import com.openbank.fetchbroker.domain.RobotsTxt
import com.openbank.libs.security.EgressRequest
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Honours robots.txt (ADR-0387 D6, RFC 9309 §2.3.1): 2xx ⇒ parse, 4xx ⇒ no restriction,
 * 5xx or unreachable ⇒ complete disallow. Cached per host for [ttl].
 */
class RobotsGate(
    private val transport: HttpTransport,
    private val userAgent: String,
    private val ttl: Duration = Duration.ofHours(1),
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Entry(val robots: RobotsTxt, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** The product token robots.txt groups are matched against: the User-Agent's first word. */
    private val token = userAgent.substringBefore('/').substringBefore(' ')

    fun isAllowed(uri: URI): Boolean {
        val host = uri.host.lowercase()
        val now = clock.millis()
        val robots = cache[host]?.takeIf { it.expiresAt > now }?.robots
            ?: load(host).also { cache[host] = Entry(it, now + ttl.toMillis()) }
        return robots.isAllowed(token, uri.rawPath?.ifEmpty { "/" } ?: "/")
    }

    @Suppress("MagicNumber")
    private fun load(host: String): RobotsTxt = try {
        val resp = transport.send(
            EgressRequest("GET", "https://$host/robots.txt", mapOf("User-Agent" to userAgent)),
            setOf(host),
        )
        when (resp.status) {
            in 200..299 -> RobotsTxt.parse(resp.bodyAsString())
            in 400..499 -> RobotsTxt.ALLOW_ALL
            else -> RobotsTxt.DENY_ALL
        }
    } catch (_: IOException) {
        RobotsTxt.DENY_ALL
    }
}
