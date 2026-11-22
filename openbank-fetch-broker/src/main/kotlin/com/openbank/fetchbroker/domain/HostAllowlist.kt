// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.domain

import java.net.URI
import java.net.URISyntaxException

sealed interface UrlDecision {
    data class Allowed(val uri: URI, val grant: HostGrant) : UrlDecision

    data class Denied(val reason: String) : UrlDecision
}

/**
 * The broker's own host check (ADR-0387 D1/D2) — applied even though OPA checked the host too, so
 * neither layer alone is trusted. Exact HTTPS hostnames only: no wildcard, no IP literal, no
 * userinfo, no non-default port, and no query string or fragment (no agent-chosen data can be
 * smuggled out in a URL).
 */
object HostAllowlist {
    private const val HTTPS_PORT = 443
    private val DNS_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun normalise(host: String): String = host.trim().lowercase().removeSuffix(".")

    /** True when [host] is an exact DNS hostname a charter may list (no wildcard, no IP literal). */
    fun isListableHost(host: String): Boolean {
        val h = normalise(host)
        if (h.isEmpty() || h.any { it in FORBIDDEN_HOST_CHARS }) return false
        val labels = h.split('.')
        if (labels.size < 2 || labels.any { !DNS_LABEL.matches(it) }) return false
        // All-numeric last label ⇒ an IPv4 literal (or a numeric-only bypass form).
        return !labels.last().all { it.isDigit() }
    }

    /** Host of [url] for policy input and metric tags; null when it cannot be parsed. */
    fun hostOf(url: String): String? = runCatching { URI(url).host?.let(::normalise) }.getOrNull()

    fun decide(url: String, grants: Map<String, HostGrant>): UrlDecision {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return UrlDecision.Denied("malformed url")
        }
        structuralDenial(url, uri)?.let { return UrlDecision.Denied(it) }
        val host = uri.host?.let(::normalise)
        return when {
            host == null || !isListableHost(host) -> UrlDecision.Denied("host must be an exact dns hostname")
            else -> grants[host]?.let { UrlDecision.Allowed(uri, it) }
                ?: UrlDecision.Denied("host not on the charter allowlist")
        }
    }

    /** Scheme, query, fragment, userinfo and port — everything except the host itself. */
    private fun structuralDenial(url: String, uri: URI): String? = when {
        !uri.isAbsolute || uri.scheme.lowercase() != "https" -> "only https urls are allowed"
        uri.rawQuery != null || '?' in url -> "query strings are not allowed"
        uri.rawFragment != null || '#' in url -> "fragments are not allowed"
        uri.rawUserInfo != null -> "userinfo is not allowed"
        uri.port != -1 && uri.port != HTTPS_PORT -> "only the default https port is allowed"
        else -> null
    }

    private const val FORBIDDEN_HOST_CHARS = "*:[]"
}
