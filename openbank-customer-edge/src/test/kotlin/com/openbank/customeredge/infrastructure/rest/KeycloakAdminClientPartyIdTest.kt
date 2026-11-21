// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Keycloak 26 refuses a partial admin PUT (an omitted root attribute counts as removed), so the
 * party_id write merges into the user's own representation. Live counterpart:
 * openbank-infra/e2e/keycloak_mcp_client_admission_test.py test_edge_service_account_can_still_write_party_id.
 */
class KeycloakAdminClientPartyIdTest {
    private val mapper = ObjectMapper()
    private val client = KeycloakAdminClient()

    @Test
    fun `party_id is set and every other field is kept`() {
        val user = """{"id":"u1","username":"a@b.c","email":"a@b.c","firstName":"A","lastName":"B",
            "enabled":true,"attributes":{"locale":["cs"],"party_id":["old"]}}"""
        val out = mapper.readTree(client.withPartyId(user, "11111111-1111-4111-8111-111111111111"))
        assertThat(
            out["attributes"]["party_id"].map {
                it.asText()
            },
        ).containsExactly("11111111-1111-4111-8111-111111111111")
        assertThat(out["attributes"]["locale"].map { it.asText() }).containsExactly("cs")
        listOf("id", "username", "email", "firstName", "lastName", "enabled").forEach {
            assertThat(out[it]).`as`(it).isEqualTo(mapper.readTree(user)[it])
        }
    }

    @Test
    fun `a user without attributes gets the attribute map`() {
        val out = mapper.readTree(client.withPartyId("""{"id":"u1","email":"a@b.c"}""", "p"))
        assertThat(out["attributes"]["party_id"].map { it.asText() }).containsExactly("p")
        assertThat(out["email"].asText()).isEqualTo("a@b.c")
    }
}
