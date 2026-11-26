// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

/** Selects the hosted dispatcher. The returned opaque token still needs live grant admission. */
internal object HostedOpaqueCredential {
    private const val BEARER_PREFIX = "Bearer "
    private val tokenFormat = Regex("obat1_[A-Za-z0-9_-]{43}")

    fun extract(headers: List<String>): String? {
        val header = headers.singleOrNull() ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return header.substring(BEARER_PREFIX.length).takeIf(tokenFormat::matches)
    }
}
