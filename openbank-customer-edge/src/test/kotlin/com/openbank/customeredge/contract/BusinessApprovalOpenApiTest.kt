// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The published contract of business signing (#10281), parsed from openapi.yaml. Route and field
 * names are literals on purpose: `BusinessPaymentApprovalIT` asserts the same names on live
 * responses, so a rename on either side reddens one of the two.
 */
class BusinessApprovalOpenApiTest {
    private val spec: JsonNode = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
    private val paths = spec.path("paths")
    private val schemas = spec.path("components").path("schemas")

    private fun JsonNode.keys(): List<String> = fieldNames().asSequence().toList()

    @Test
    fun `every business signing route is published with its method`() {
        val expected = mapOf(
            "/business/signing-policy" to listOf("get", "put"),
            "/business/trusted-payees" to listOf("get", "post"),
            "/business/trusted-payees/{id}" to listOf("delete"),
            "/business/approvals" to listOf("get"),
            "/business/approvals/{id}" to listOf("get"),
            "/business/approvals/{id}/sign" to listOf("post"),
            "/business/approvals/{id}/reject" to listOf("post"),
            "/me/approvals/pending" to listOf("get"),
        )
        expected.forEach { (path, methods) ->
            assertThat(paths.path(path).keys()).describedAs(path).containsAll(methods)
        }
    }

    @Test
    fun `business routes require X-Acting-For, the personal pending list does not take it`() {
        listOf("/business/signing-policy", "/business/approvals/{id}/sign", "/business/trusted-payees").forEach { p ->
            val op = paths.path(p).elements().next()
            val header = op.path("parameters").first { it.path("name").asText() == "X-Acting-For" }
            assertThat(header.path("required").asBoolean()).describedAs(p).isTrue()
        }
        val mine = paths.path("/me/approvals/pending").path("get").path("parameters")
        assertThat(mine.none { it.path("name").asText() == "X-Acting-For" }).isTrue()
    }

    @Test
    fun `sign requires the SCA challenge header and documents the refusals`() {
        val sign = paths.path("/business/approvals/{id}/sign").path("post")
        assertThat(sign.path("parameters").any { it.path("name").asText() == "X-SCA-Challenge-Id" }).isTrue()
        assertThat(sign.path("responses").keys()).contains("200", "403", "409")
    }

    @Test
    fun `the four payment rails document the 202 hold and the fail-closed 503`() {
        listOf("/domestic-payments", "/sepa-payments", "/sepa-instant", "/swift").forEach { p ->
            val responses = paths.path(p).path("post").path("responses")
            assertThat(responses.keys()).describedAs(p).contains("201", "202", "503")
            assertThat(
                responses.path("202").path("content").path("application/json").path("schema").path("\$ref").asText(),
            )
                .isEqualTo("#/components/schemas/PaymentPendingApproval")
        }
        assertThat(schemas.path("PaymentPendingApproval").path("properties").keys())
            .containsExactlyInAnyOrder("approvalId", "status", "payloadSha256", "required", "collected", "expiresAt")
    }

    @Test
    fun `non-payment requests are created AWAITING_INITIATOR and lists are wrapped in data`() {
        listOf(
            paths.path("/business/signing-policy").path("put"),
            paths.path("/business/trusted-payees").path("post"),
            paths.path("/business/trusted-payees/{id}").path("delete"),
        ).forEach { op ->
            assertThat(
                op.path(
                    "responses",
                ).path("202").path("content").path("application/json").path("schema").path("\$ref").asText(),
            )
                .isEqualTo("#/components/schemas/ApprovalCreated")
        }
        assertThat(schemas.path("ApprovalCreated").path("properties").path("status").path("enum").map { it.asText() })
            .containsExactly("AWAITING_INITIATOR")
        listOf("/business/approvals", "/business/trusted-payees", "/me/approvals/pending").forEach { p ->
            val schema = paths.path(
                p,
            ).path("get").path("responses").path("200").path("content").path("application/json").path("schema")
            assertThat(schema.path("properties").path("data").path("type").asText()).describedAs(p).isEqualTo("array")
        }
    }

    @Test
    fun `the approval detail carries what the signer screen renders`() {
        assertThat(schemas.path("BusinessApprovalRequest").path("properties").keys())
            .contains("id", "kind", "status", "payload", "payloadSha256", "signers", "canSign")
            .contains("collected", "summaryText", "release")
        assertThat(schemas.path("BusinessApprovalSigner").path("properties").keys())
            .containsExactlyInAnyOrder("partyId", "name", "status", "signedAt", "isYou", "isInitiator")
        assertThat(schemas.path("SigningPolicy").path("properties").keys()).contains("summary", "isDerivedFromRegister")
    }
}
