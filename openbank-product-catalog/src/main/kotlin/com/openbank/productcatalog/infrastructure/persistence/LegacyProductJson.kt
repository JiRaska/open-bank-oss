package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.productcatalog.domain.Product
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Read-repair for legacy `products.doc` JSONB rows written before `createdAt`/`updatedAt`
 * became required domain fields (#8357 EPOCH-default burn-down). A document persisted by an
 * older build carries no timestamps at all, and a strict Jackson read would fail the whole
 * row — poisoning even `findAll` for every sibling row. The repair derives the most truthful
 * value available: the product's own `validFrom` at start of day UTC; only when the document
 * has no validity window either do we fall back to the epoch, which here means "legacy row,
 * timestamp unknown" and is written by this repair alone — never by a domain default.
 */
object LegacyProductJson {
    fun readProduct(mapper: ObjectMapper, doc: String): Product {
        val node = mapper.readTree(doc)
        if (node is ObjectNode) {
            backfillTimestamps(node)
        }
        return mapper.treeToValue(node, Product::class.java)
    }

    private fun backfillTimestamps(node: ObjectNode) {
        val created = node.get("createdAt")?.takeIf { it.isTextual }?.let { Instant.parse(it.asText()) }
            ?: legacyInstant(node.get("validFrom")?.takeIf { it.isTextual }?.asText())
        if (!node.has("createdAt") || node.get("createdAt").isNull) {
            node.put("createdAt", created.toString())
        }
        if (!node.has("updatedAt") || node.get("updatedAt").isNull) {
            node.put("updatedAt", created.toString())
        }
        (node.get("versionHistory") as? com.fasterxml.jackson.databind.node.ArrayNode)?.forEach { version ->
            if (version is ObjectNode && (!version.has("createdAt") || version.get("createdAt").isNull)) {
                version.put(
                    "createdAt",
                    legacyInstant(version.get("validFrom")?.takeIf { it.isTextual }?.asText()).toString(),
                )
            }
        }
    }

    private fun legacyInstant(validFrom: String?): Instant = validFrom
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.atStartOfDay(ZoneOffset.UTC)
        ?.toInstant()
        ?: Instant.EPOCH
}
