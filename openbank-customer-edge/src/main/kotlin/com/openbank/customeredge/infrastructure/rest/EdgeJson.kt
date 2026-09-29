// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.time.Instant

/**
 * Small JSON helpers for resources that PROJECT an upstream body into a customer-facing shape
 * instead of passing it through. Projection is a whitelist: a field an upstream adds later does
 * not reach the app until someone names it here.
 *
 * Floats are read as [BigDecimal] so a money amount never passes through a binary double.
 */
internal object EdgeJson {
    val mapper: ObjectMapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    private const val UPSTREAM_SERVER_ERROR_MIN = 500
    private const val BAD_GATEWAY = 502

    fun parse(response: Response): JsonNode? =
        (response.entity as? String)?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    fun parseObject(body: String?): JsonNode? =
        body?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    fun ok(value: Any, status: Int = Response.Status.OK.statusCode): Response =
        Response.status(status).entity(mapper.writeValueAsString(value)).type(MediaType.APPLICATION_JSON).build()

    fun error(status: Int, message: String, extra: Map<String, Any?> = emptyMap()): Response =
        ok(mapOf("error" to message) + extra, status)

    /**
     * A non-success upstream answer the route has no specific mapping for. The upstream body is
     * NOT forwarded — it was written for an operator-realm caller and may name things the customer
     * must not see.
     */
    fun upstreamFailure(response: Response, service: String): Response = when {
        response.status == Response.Status.NOT_FOUND.statusCode -> error(response.status, "not found")
        response.status >= UPSTREAM_SERVER_ERROR_MIN -> error(BAD_GATEWAY, "$service unavailable")
        else -> error(BAD_GATEWAY, "unexpected $service response")
    }

    fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.textValue()

    fun JsonNode.int(field: String): Int? = path(field).takeIf { it.isIntegralNumber }?.intValue()

    fun JsonNode.instant(field: String): Instant? = text(field)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** A decimal as a plain string ("500", "12.5"), whether upstream sent a number or a string. */
    fun JsonNode.decimalString(field: String): String? {
        val node = path(field)
        val value = when {
            node.isNumber -> node.decimalValue()
            node.isTextual -> runCatching { BigDecimal(node.textValue()) }.getOrNull()
            else -> null
        }
        return value?.stripTrailingZeros()?.toPlainString()
    }
}
