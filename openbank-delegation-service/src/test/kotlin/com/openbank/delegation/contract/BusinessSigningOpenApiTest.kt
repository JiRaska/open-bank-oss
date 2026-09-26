// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import com.openbank.delegation.domain.model.ApprovalKind
import com.openbank.delegation.domain.model.ApprovalStatus
import com.openbank.delegation.infrastructure.rest.BusinessSigningResource
import com.openbank.delegation.infrastructure.rest.PendingApprovalsResource
import com.openbank.delegation.infrastructure.rest.dto.ApprovalRequestResponse
import com.openbank.delegation.infrastructure.rest.dto.EvaluationResponse
import com.openbank.delegation.infrastructure.rest.dto.PendingForHumanResponse
import com.openbank.delegation.infrastructure.rest.dto.ReleaseClaimResponse
import com.openbank.delegation.infrastructure.rest.dto.SigningPolicyResponse
import com.openbank.delegation.infrastructure.rest.dto.TrustedPayeeResponse
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * ADR-0312 contract, both directions: every served signing route is documented and every
 * documented one is served; every response DTO carries exactly the properties its schema promises;
 * and the status/kind enums equal the domain's. A route or field added on one side only fails here.
 */
@Suppress("UNCHECKED_CAST")
class BusinessSigningOpenApiTest {
    private val spec: Map<String, Any?> = Yaml().load(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
    private val paths = spec["paths"] as Map<String, Map<String, Any?>>
    private val schemas = (spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>

    private fun served(resource: KClass<*>): Set<String> {
        val base = resource.java.getAnnotation(Path::class.java).value
        return resource.java.declaredMethods.mapNotNull { m ->
            val verb = when {
                m.isAnnotationPresent(GET::class.java) -> "get"
                m.isAnnotationPresent(POST::class.java) -> "post"
                m.isAnnotationPresent(PUT::class.java) -> "put"
                m.isAnnotationPresent(DELETE::class.java) -> "delete"
                else -> null
            } ?: return@mapNotNull null
            "$verb $base${m.getAnnotation(Path::class.java)?.value.orEmpty()}"
        }.toSet()
    }

    private fun documented(): Set<String> = paths.filterKeys {
        it.startsWith("/api/v1/entities/") || it == "/api/v1/parties/{humanId}/approval-requests/pending"
    }.flatMap { (path, ops) -> ops.keys.map { "$it $path" } }.toSet()

    private fun properties(schema: String): Set<String> =
        (schemas.getValue(schema)["properties"] as Map<String, Any?>).keys

    private fun fields(type: KClass<*>): Set<String> = type.primaryConstructor!!.parameters.map { it.name!! }.toSet()

    @Test
    fun `every served signing route is documented and every documented one is served`() {
        val served = served(BusinessSigningResource::class) + served(PendingApprovalsResource::class)
        assertThat(served).hasSize(EXPECTED_OPERATIONS)
        assertThat(documented()).isEqualTo(served)
    }

    @Test
    fun `response DTOs carry exactly the documented properties`() {
        assertThat(fields(ApprovalRequestResponse::class)).isEqualTo(properties("ApprovalRequest"))
        assertThat(fields(EvaluationResponse::class)).isEqualTo(properties("SigningEvaluation"))
        assertThat(fields(SigningPolicyResponse::class)).isEqualTo(properties("SigningPolicy"))
        assertThat(fields(TrustedPayeeResponse::class)).isEqualTo(properties("TrustedPayee"))
        assertThat(fields(PendingForHumanResponse::class)).isEqualTo(properties("PendingApprovals"))
        assertThat(fields(ReleaseClaimResponse::class)).isEqualTo(properties("ReleaseClaim"))
    }

    @Test
    fun `status and kind enums are the domain's`() {
        assertThat(schemas.getValue("ApprovalStatus")["enum"] as List<String>).containsExactlyElementsOf(
            ApprovalStatus.entries.map {
                it.name
            },
        )
        assertThat(schemas.getValue("ApprovalKind")["enum"] as List<String>).containsExactlyElementsOf(
            ApprovalKind.entries.map {
                it.name
            },
        )
    }

    @Test
    fun `the release claim is documented as single-use`() {
        val claim = (
            paths.getValue(
                "/api/v1/entities/{entityId}/approval-requests/{approvalId}/release-claim",
            )["post"] as Map<String, Any?>
            )
        assertThat((claim["responses"] as Map<String, Any?>).keys).contains("200", "409")
        assertThat(claim["description"] as String).contains("Exactly one caller")
    }

    /**
     * The negative half (ADR-0279 #3): every state-changing signing route documents the 403 a
     * wrong identity gets (not eligible, mandate lapsed, SCA not linked to this request), and every
     * route addressed at one approval documents the 404 a foreign entity gets.
     */
    @Test
    fun `wrong identity and foreign resources are documented refusals`() {
        val mutating = listOf(
            "/api/v1/entities/{entityId}/approval-requests" to "post",
            "/api/v1/entities/{entityId}/approval-requests/{approvalId}/signatures" to "post",
            "/api/v1/entities/{entityId}/approval-requests/{approvalId}/rejection" to "post",
            "/api/v1/entities/{entityId}/signing-policy" to "put",
            "/api/v1/entities/{entityId}/trusted-payees" to "post",
        )
        mutating.forEach { (path, verb) ->
            val responses = (paths.getValue(path)[verb] as Map<String, Any?>)["responses"] as Map<String, Any?>
            assertThat(responses.keys).describedAs("$verb $path").contains("403")
        }
        listOf("signatures", "rejection", "release-claim", "release-result").forEach { action ->
            val op = paths.getValue(
                "/api/v1/entities/{entityId}/approval-requests/{approvalId}/$action",
            )["post"] as Map<String, Any?>
            assertThat((op["responses"] as Map<String, Any?>).keys).describedAs(action).contains("404")
        }
    }

    private companion object {
        const val EXPECTED_OPERATIONS = 16
    }
}
