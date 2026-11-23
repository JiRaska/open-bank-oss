// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Keycloak 26 refuses a partial admin PUT (an omitted root attribute counts as removed), so the
 * party_id write merges into the user's own profile fields — and only those. Live counterpart:
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
        listOf("username", "email", "firstName", "lastName").forEach {
            assertThat(out[it]).`as`(it).isEqualTo(mapper.readTree(user)[it])
        }
    }

    @Test
    fun `account state is absent from the PUT so a concurrent disable is not reverted`() {
        // Review of #16672: the body is built from a GET; anything it echoes back is a stale copy
        // that would overwrite a change made between the GET and the PUT (an admin disabling the
        // user, adding a required action, resetting credentials). Keycloak leaves omitted fields as
        // they are, so they must be omitted.
        val user = """{"id":"u1","username":"a@b.c","email":"a@b.c","firstName":"A","lastName":"B",
            "enabled":true,"emailVerified":true,"totp":false,"requiredActions":[],
            "credentials":[{"type":"password","value":"x"}],"federationLink":"ldap",
            "access":{"manageGroup":true},"notBefore":0,"createdTimestamp":1,
            "disableableCredentialTypes":[],"attributes":{"party_id":["old"]}}"""
        val out = mapper.readTree(client.withPartyId(user, "p"))
        listOf(
            "enabled", "credentials", "requiredActions", "emailVerified", "totp", "federationLink",
            "access", "notBefore", "createdTimestamp", "disableableCredentialTypes", "id",
        ).forEach { assertThat(out.has(it)).`as`("$it must not be sent").isFalse() }
        assertThat(out.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("username", "email", "firstName", "lastName", "attributes")
    }

    @Test
    fun `a user without attributes gets the attribute map`() {
        val out = mapper.readTree(client.withPartyId("""{"id":"u1","email":"a@b.c"}""", "p"))
        assertThat(out["attributes"]["party_id"].map { it.asText() }).containsExactly("p")
        assertThat(out["email"].asText()).isEqualTo("a@b.c")
    }
}
