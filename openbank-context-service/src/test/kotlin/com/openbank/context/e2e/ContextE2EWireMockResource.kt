// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.util.UUID

/**
 * Stands in for `aml-service` over **real HTTP** so the evidence resource's bounded, uncached
 * current-state check (`AmlCaseSourceStatus.isOpen`) actually leaves the process. This is the part
 * a mocked `AmlCaseSourceStatus` cannot establish: whether the owning service still considers the
 * case OPEN decides whether historical evidence may be read at all, and only a real HTTP stub can
 * move a case from open to closed between two reads.
 *
 * Unstubbed case ids answer 404, which `AmlCaseSourceStatus` maps to "not open" — the fail-closed
 * default every test gets for free until it pins a status explicitly.
 */
class ContextE2EWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val server = WireMockServer(options().dynamicPort())
        server.start()
        server.stubFor(get(urlEqualTo("/api/v1/aml/cases/unknown")).willReturn(notFound()))
        Companion.server = server
        return mapOf("quarkus.rest-client.aml-service.url" to server.baseUrl())
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        private var server: WireMockServer? = null

        /** Pins the current source state for one case; other ids keep the 404 default. */
        fun stubCaseStatus(id: UUID, status: String) {
            require(status in setOf("OPEN", "UNDER_REVIEW", "ESCALATED", "CLEARED", "BLOCKED")) {
                "unknown AML case status $status"
            }
            val target = server ?: error("WireMock not started")
            target.stubFor(
                get(urlEqualTo("/api/v1/aml/cases/$id")).willReturn(
                    okJson("""{"id":"$id","status":"$status"}"""),
                ),
            )
        }
    }
}
