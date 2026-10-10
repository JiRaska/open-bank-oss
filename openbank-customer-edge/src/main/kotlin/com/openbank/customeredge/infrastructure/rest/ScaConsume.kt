// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * The edge's one call into sca-service's settlement gate (ADR-0021): spend an SCA challenge on
 * exactly the operation it authorised. sca-service atomically verifies the challenge is approved,
 * belongs to [human], and that its device-signed dynamic-linking data matches every field sent here
 * (amount/currency/creditor for a payment, approvalRequestId/payloadSha256 for an operation-bound
 * approval), then marks it consumed — single use, server-side. A mismatch answers 409 and does not
 * burn the challenge.
 *
 * Shared by the payment gate ([CustomerEdgeResource]) and the pension routes
 * ([CustomerPensionResource]) so the consume contract has one implementation.
 */
internal object ScaConsume {
    const val HEADER = "X-SCA-Challenge-Id"

    fun challengeId(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }

    /** POST the consume. [linking] values that are null are omitted, exactly as the payment gate always did. */
    fun consume(
        upstream: UpstreamClient,
        scaServiceUrl: String,
        challengeId: UUID,
        callerParty: String,
        human: UUID,
        linking: Map<String, String?>,
    ): Response {
        val body = linkedMapOf<String, String>("partyId" to human.toString())
        linking.forEach { (k, v) -> if (v != null) body[k] = v }
        return upstream.post(
            "${scaServiceUrl.trimEnd('/')}/api/v1/sca/challenges/$challengeId/consume",
            callerParty,
            EdgeJson.mapper.writeValueAsString(body),
        )
    }

    fun required(extra: Map<String, Any?> = emptyMap()): Response = EdgeJson.error(
        Response.Status.FORBIDDEN.statusCode,
        "Strong customer authentication required",
        mapOf("code" to "SCA_REQUIRED") + extra,
    )

    fun rejected(): Response = EdgeJson.error(
        Response.Status.FORBIDDEN.statusCode,
        "Strong customer authentication failed",
        mapOf("code" to "SCA_REJECTED"),
    )
}
