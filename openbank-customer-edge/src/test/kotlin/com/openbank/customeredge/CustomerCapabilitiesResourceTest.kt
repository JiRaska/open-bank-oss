// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CapabilityCatalog
import com.openbank.customeredge.infrastructure.rest.CustomerCapabilitiesResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Optional

/**
 * ADR-0310 D5. The property that matters most is the negative one: a capability whose backend is
 * not wired never reads as live, whatever its switch says. `a switched-on capability with no
 * backend is NOT_DEPLOYED` is red if the URL guard is dropped from `CapabilityCatalog.backed`.
 */
class CustomerCapabilitiesResourceTest {
    private val mapper = ObjectMapper()

    private fun resource(
        loyaltyOn: Boolean = false,
        loyaltyUrl: String? = null,
        referralsOn: Boolean = false,
        referralUrl: String? = "https://referral-service.referral.svc:8443",
    ) = CustomerCapabilitiesResource(
        loyaltyOn,
        referralsOn,
        Optional.ofNullable(loyaltyUrl),
        Optional.ofNullable(referralUrl),
    )

    private fun body(r: CustomerCapabilitiesResource) = mapper.readTree(r.capabilities().entity as String)

    private fun stateOf(r: CustomerCapabilitiesResource, id: String): Pair<String, String?> {
        val node = body(r)["capabilities"].first { it["id"].asText() == id }
        return node["state"].asText() to node["reason"]?.asText()
    }

    @Test
    fun `today's configuration publishes exactly the ADR's JSON`() {
        val response = resource().capabilities()

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("private, max-age=300")
        assertThat(mapper.readTree(response.entity as String)).isEqualTo(
            mapper.readTree(
                """
                {"schemaVersion":1,"capabilities":[
                  {"id":"loyalty","state":"unavailable","reason":"NOT_DEPLOYED"},
                  {"id":"referrals","state":"unavailable","reason":"DISABLED"},
                  {"id":"offers","state":"unavailable","reason":"NOT_BUILT"},
                  {"id":"accept.settlement","state":"unavailable","reason":"NOT_BUILT"},
                  {"id":"allmoney.linked","state":"unavailable","reason":"NOT_BUILT"},
                  {"id":"business.tax","state":"unavailable","reason":"NOT_BUILT"},
                  {"id":"approvals.multisig","state":"unavailable","reason":"NOT_BUILT"},
                  {"id":"rewards.points","state":"unavailable","reason":"NOT_BUILT"}
                ]}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a switched-on capability with no backend is NOT_DEPLOYED`() {
        listOf(null, "", "  ").forEach { url ->
            assertThat(stateOf(resource(loyaltyOn = true, loyaltyUrl = url), "loyalty"))
                .isEqualTo("unavailable" to "NOT_DEPLOYED")
        }
    }

    @Test
    fun `a wired capability an operator has not switched on is DISABLED`() {
        assertThat(stateOf(resource(loyaltyOn = false, loyaltyUrl = "https://loyalty"), "loyalty"))
            .isEqualTo("unavailable" to "DISABLED")
    }

    @Test
    fun `a wired and switched-on capability is live and carries no reason`() {
        val r = resource(loyaltyOn = true, loyaltyUrl = "https://loyalty", referralsOn = true)
        assertThat(stateOf(r, "loyalty")).isEqualTo("live" to null)
        assertThat(stateOf(r, "referrals")).isEqualTo("live" to null)
        assertThat(body(r)["capabilities"].first { it["id"].asText() == "loyalty" }.has("reason")).isFalse()
    }

    @Test
    fun `switching one capability does not move another`() {
        assertThat(stateOf(resource(loyaltyOn = true, loyaltyUrl = "https://loyalty"), "referrals"))
            .isEqualTo("unavailable" to "DISABLED")
    }

    /** The spec's closed enum and the catalogue must name the same ids, in both directions. */
    @Test
    fun `the published ids match the spec enum`() {
        val spec = File("src/main/resources/openapi.yaml").readText()
        val block = spec.substringAfter("    CapabilityId:").substringBefore("\n    Capability:")
        val specIds = Regex("""enum: \[([^\]]*)]""").find(block)!!.groupValues[1].split(",").map { it.trim() }
        assertThat(specIds).containsExactlyElementsOf(CapabilityCatalog.IDS)
    }
}
