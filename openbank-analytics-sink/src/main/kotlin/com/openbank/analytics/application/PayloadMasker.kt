// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.security.MaskStrategy
import com.openbank.libs.security.PiiMask

/**
 * Masks PII in an inbound event body *before* it is written to the analytics bronze layer.
 *
 * The bronze layer is retained for ≥10 years (ADR-0022 / [com.openbank.libs.analytics.AnalyticsRetention]),
 * so it must NEVER hold raw PII — a long-lived analytics store is the worst place for an
 * un-erasable identifier (GDPR Art. 25 data-protection-by-design, Art. 17 erasability). We mask
 * at the sink boundary, by field name, using the same [PiiMask] strategies the rest of the platform
 * uses, so the masking is consistent and irreversible.
 *
 * This is intentionally a *conservative allow-by-default-mask* of known PII key names: an
 * unrecognised field passes through structurally (it still carries analytic value), while anything
 * that looks like a direct identifier is masked. Producing services that emit additional PII should
 * either pre-mask or have their key added here (see [PII_KEYS]).
 */
object PayloadMasker {

    /** Field-name (lower-cased) → masking strategy. Order doesn't matter; lookup is exact-ish. */
    private val PII_KEYS: Map<String, MaskStrategy> = mapOf(
        "email" to MaskStrategy.EMAIL,
        "emailaddress" to MaskStrategy.EMAIL,
        "iban" to MaskStrategy.IBAN,
        "accountnumber" to MaskStrategy.IBAN,
        "pan" to MaskStrategy.PAN,
        "cardnumber" to MaskStrategy.PAN,
        "phone" to MaskStrategy.PHONE,
        "phonenumber" to MaskStrategy.PHONE,
        "msisdn" to MaskStrategy.PHONE,
        "name" to MaskStrategy.NAME,
        "fullname" to MaskStrategy.NAME,
        "firstname" to MaskStrategy.NAME,
        "lastname" to MaskStrategy.NAME,
        // ADR-0284 business onboarding. `signerName` is a named human. `legalName` is a company
        // name for every legal form EXCEPT a sole trader, where it is that person's own name —
        // and the field name cannot tell the two apart, so it is masked in both cases. The
        // identifier (IČO/CRN/LEI) is not masked, so a company stays joinable in the warehouse.
        "signername" to MaskStrategy.NAME,
        "legalname" to MaskStrategy.NAME,
        "nationalid" to MaskStrategy.NATIONAL_ID,
        "birthnumber" to MaskStrategy.NATIONAL_ID,
        "rodnecislo" to MaskStrategy.NATIONAL_ID,
        "ssn" to MaskStrategy.NATIONAL_ID,
    )

    /** Recursively converts a JSON event body into a masked `Map`, masking any [PII_KEYS] leaf. */
    fun maskToMap(node: JsonNode?): Map<String, Any?> {
        if (node == null || !node.isObject) return emptyMap()
        return convertObject(node as ObjectNode)
    }

    private fun convertObject(obj: ObjectNode): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.size())
        obj.fieldNames().forEach { field ->
            val strategy = PII_KEYS[field.lowercase()]
            val child = obj.get(field)
            out[field] = when {
                strategy != null && child.isTextual -> PiiMask.apply(strategy, child.asText())
                strategy != null && !child.isNull -> PiiMask.apply(strategy, child.asText())
                else -> convertValue(child)
            }
        }
        return out
    }

    private fun convertValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isObject -> convertObject(node as ObjectNode)
        node.isArray -> (node as ArrayNode).map { convertValue(it) }
        node.isBoolean -> node.asBoolean()
        node.isInt || node.isLong -> node.asLong()
        node.isFloatingPointNumber -> node.asDouble()
        else -> node.asText()
    }
}
