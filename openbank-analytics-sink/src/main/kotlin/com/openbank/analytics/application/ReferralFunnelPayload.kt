// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * Strict allowlist for referral events before the ten-year bronze write. The source event contains
 * referrerPartyId and refereePartyId; neither is needed to count the lifecycle funnel. Never copy
 * the raw node or add an unknown field by default.
 */
internal object ReferralFunnelPayload {
    private val typeByTopic = mapOf(
        "openbank.referral.qualified.v1" to "Qualified",
        "openbank.referral.reward-requested.v1" to "RewardRequested",
        "openbank.referral.reward-outcome.v1" to "RewardOutcome",
    )
    val topics: Set<String> = typeByTopic.keys

    fun topicForBody(node: JsonNode): String? {
        val body = node["payload"] ?: node
        val referral = node["sourceService"]?.asText() == "referral-service" ||
            body.has("referrerPartyId") ||
            body.has("refereePartyId") ||
            (body.has("programId") && body.has("inviteId"))
        if (!referral) return null
        require(body === node) { "nested referral payload requires explicit projection" }
        return typeByTopic.entries.firstOrNull { it.value == node["eventType"]?.asText() }?.key
            ?: throw IllegalArgumentException("unsupported referral lifecycle event")
    }

    fun project(node: JsonNode, topic: String): Map<String, Any?> {
        val eventId = UUID.fromString(requireText(node, "eventId"))
        val programId = UUID.fromString(requireText(node, "programId"))
        val eventType = requireText(node, "eventType")
        require(eventType == typeByTopic[topic]) {
            "referral lifecycle type does not match topic"
        }
        val version = node["programVersion"]?.takeUnless { it.isNull }?.let {
            require(it.isIntegralNumber && it.canConvertToInt() && it.asInt() > 0) {
                "invalid referral programVersion"
            }
            it.asInt()
        }
        val outcome = if (eventType == "RewardOutcome") {
            requireText(node, "outcome").also {
                require(it in setOf("ACCEPTED", "REJECTED", "REVERSED")) { "invalid referral outcome" }
            }
        } else {
            null
        }
        return mapOf(
            "eventId" to eventId.toString(),
            "programId" to programId.toString(),
            "programVersion" to version,
            "state" to eventType,
            "outcome" to outcome,
        )
    }

    private fun requireText(node: JsonNode, name: String): String =
        node[name]?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            ?: throw IllegalArgumentException("missing referral $name")
}
