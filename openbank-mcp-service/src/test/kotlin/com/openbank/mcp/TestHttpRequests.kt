// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.impl.headers.HeadersMultiMap

/**
 * A stand-in for the CDI-injected request on a hand-built McpEndpoint: the staff root refuses a
 * missing request (fail closed), so every direct-call test supplies one.
 */
object TestHttpRequests {
    fun withHeaders(vararg headers: Pair<String, String>): HttpServerRequest {
        val map = HeadersMultiMap.httpHeaders()
        headers.forEach { (k, v) -> map.add(k, v) }
        return mockk(relaxed = true) {
            every { headers() } returns map
            every { getHeader(any<String>()) } answers { map.get(firstArg<String>()) }
        }
    }
}
