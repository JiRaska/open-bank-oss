// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.openbank.campaign.domain.model.SegmentRule

/**
 * Persistence format for [SegmentRule], owned by the adapter rather than the domain (ADR-0002).
 *
 * `SegmentRule` is a sealed hierarchy, and Jackson cannot deserialise one without type information:
 * reading a stored segment threw
 *
 *     Cannot construct instance of `SegmentRule` (no Creators, like default constructor, exist):
 *     abstract types either need to be mapped to concrete types, have custom deserializer, or
 *     contain additional type information
 *
 * The usual fix is `@JsonTypeInfo`/`@JsonSubTypes` on the sealed class, but the domain layer carries
 * zero framework imports, so annotating it there is not available. A Jackson mixin would work and
 * keep the domain clean; this does the same job without the indirection, and — the reason it is
 * preferred here — it fails loudly and specifically on a rule name it does not recognise, instead of
 * yielding a half-built object. The mapping is deliberately explicit: adding a `SegmentRule` without
 * touching this file is a compile error on the `when`, not a silent runtime gap.
 *
 * Format: `[{"type":"PartyStatusIs","status":"ACTIVE"}, {"type":"TenureAtLeastDays","minDays":30}]`
 */
object SegmentRuleSerde {

    private const val TYPE = "type"

    fun write(mapper: ObjectMapper, rules: List<SegmentRule>): String {
        val array = mapper.createArrayNode()
        rules.forEach { rule ->
            val node = array.addObject()
            when (rule) {
                is SegmentRule.PartyStatusIs -> {
                    node.put(TYPE, "PartyStatusIs")
                    node.put("status", rule.status)
                }

                is SegmentRule.TenureAtLeastDays -> {
                    node.put(TYPE, "TenureAtLeastDays")
                    node.put("minDays", rule.minDays)
                }

                // Unsupported rules are rejected by Segment's constructor, so a persisted segment can
                // never contain one. Writing them anyway would put a rule in the database that can
                // never be loaded back (issue #2891).
                is SegmentRule.HasAccount,
                is SegmentRule.HasActiveConsentScope,
                -> throw IllegalArgumentException(
                    "rule ${rule::class.simpleName} cannot be persisted: ${rule.unsupportedReason}",
                )
            }
        }
        return mapper.writeValueAsString(array)
    }

    fun read(mapper: ObjectMapper, json: String): List<SegmentRule> {
        val array = mapper.readTree(json) as? ArrayNode
            ?: throw IllegalArgumentException(
                "segment rules must be a JSON array, got: ${json.take(ERROR_SNIPPET_LENGTH)}",
            )
        return array.map { node ->
            when (val type = node[TYPE]?.asText()) {
                "PartyStatusIs" -> SegmentRule.PartyStatusIs(field(node, "status", type).asText())
                "TenureAtLeastDays" -> SegmentRule.TenureAtLeastDays(field(node, "minDays", type).asLong())
                else -> throw IllegalArgumentException(
                    "unknown segment rule type: ${type ?: "<missing `type`>"}",
                )
            }
        }
    }

    private fun field(node: JsonNode, name: String, ruleType: String): JsonNode =
        node[name] ?: throw IllegalArgumentException("$ruleType requires `$name`")

    private const val ERROR_SNIPPET_LENGTH = 80
}
