// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import jakarta.ws.rs.Path

/**
 * Resolves the path a REST-client interface will ACTUALLY request, out of its own JAX-RS
 * annotations.
 *
 * ## This is for the outgoing request only — never for the expectation
 *
 * A Pact mock server answers whatever path the client asks for, so a consumer test whose
 * expectation is *also* derived from the client annotation cannot fail: expectation and request
 * move together (CLAUDE.md, measured on #2290). Every consumer test in this package therefore
 * writes the expected `.path(...)` as a LITERAL and sends the request through [of]. Point
 * `ScaServiceRestClient` at a route sca-service does not serve and the two disagree: the mock
 * server 404s and the test goes red at the consumer layer, before the provider replay ever runs.
 *
 * The asymmetry IS the test. Do not "tidy" it by sharing one constant between the two sides.
 */
internal object ClientRoute {

    fun of(clientInterface: Class<*>, method: String, vararg pathParams: Pair<String, String>): String {
        val classPath = requireNotNull(clientInterface.getAnnotation(Path::class.java)) {
            "${clientInterface.name} carries no @Path — it is not a JAX-RS client interface"
        }.value
        val declared = clientInterface.methods.firstOrNull { it.name == method }
            ?: error("${clientInterface.name} declares no method named $method")
        val methodPath = declared.getAnnotation(Path::class.java)?.value.orEmpty()
        val joined = "/" + listOf(classPath, methodPath)
            .flatMap { it.split("/") }
            .filter { it.isNotBlank() }
            .joinToString("/")
        return pathParams.fold(joined) { acc, (name, value) -> acc.replace("{$name}", value) }
    }
}
