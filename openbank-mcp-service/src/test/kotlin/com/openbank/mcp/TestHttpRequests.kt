// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.MultiMap
import io.vertx.core.http.HttpServerRequest

/**
 * A request carrying the given headers (none by default), for unit tests that build [McpEndpoint]
 * by hand. The endpoint refuses a request whose headers it cannot read (fail closed), so every such
 * test must supply one.
 */
object TestHttpRequests {
    fun withHeaders(vararg headers: Pair<String, String>): HttpServerRequest {
        val map = MultiMap.caseInsensitiveMultiMap().apply { headers.forEach { add(it.first, it.second) } }
        return mockk(relaxed = true) {
            every { getHeader(any<String>()) } answers { map.get(firstArg<String>()) }
            every { headers() } returns map
        }
    }
}
