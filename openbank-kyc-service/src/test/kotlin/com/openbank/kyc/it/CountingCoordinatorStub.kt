// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.it

import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A case-coordinator that answers 201 and remembers every request body, so a test can count how
 * many opens reached the coordinator per party (the body carries the party id as `subjectRef`).
 */
class CountingCoordinatorStub : QuarkusTestResourceLifecycleManager {
    private var server: HttpServer? = null

    override fun start(): Map<String, String> {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/api/v1/case-coordinator/cases") { exchange ->
            requests.add(String(exchange.requestBody.readAllBytes()))
            val bytes = """{"caseId":"synthetic-case-${UUID.randomUUID()}"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        http.start()
        server = http
        val url = "http://127.0.0.1:${http.address.port}"
        return mapOf(
            "quarkus.rest-client.case-coordinator.url" to url,
            "openbank.kyc.case-coordinator.url" to url,
        )
    }

    override fun stop() {
        server?.stop(0)
    }

    companion object {
        val requests = CopyOnWriteArrayList<String>()

        fun opensFor(partyId: UUID): Int = requests.count { it.contains(partyId.toString()) }
    }
}
