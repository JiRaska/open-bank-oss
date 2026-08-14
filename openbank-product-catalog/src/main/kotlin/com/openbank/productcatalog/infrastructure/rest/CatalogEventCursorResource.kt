// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.productcatalog.generated.api.CatalogEventsApi
import com.openbank.productcatalog.generated.model.CatalogEvent
import com.openbank.productcatalog.generated.model.CatalogEventPage
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** Pull-based delivery for standalone installs that do not operate Kafka or an outbox dispatcher. */
@Blocking
@ApplicationScoped
class CatalogEventCursorResource(private val dataSource: DataSource, private val mapper: ObjectMapper) :
    CatalogEventsApi {
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read")
    override suspend fun listCatalogEvents(after: String?, limit: Int): Response {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        val cursor = after?.let(::decodeCursor)
        val items = dataSource.connection.use { connection ->
            val sql = buildString {
                append(
                    "SELECT id, aggregate_type, aggregate_id, event_type, schema_version, " +
                        "occurred_at, headers, payload, cursor_position FROM catalog_outbox ",
                )
                if (cursor != null) append("WHERE cursor_position > ? ")
                append("ORDER BY cursor_position LIMIT ?")
            }
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                cursor?.let { statement.setLong(index++, it.position) }
                statement.setInt(index, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toEvent()) } }
            }
        }
        return Response.ok(
            CatalogEventPage(
                items = items.map(CatalogEventRow::event),
                nextCursor = items.lastOrNull()?.let { encodeCursor(it.position) } ?: after,
            ),
        ).build()
    }

    private fun ResultSet.toEvent() = CatalogEventRow(
        CatalogEvent(
            id = getObject("id", UUID::class.java),
            aggregateType = getString("aggregate_type"),
            aggregateId = getObject("aggregate_id", UUID::class.java),
            eventType = getString("event_type"),
            schemaVersion = getInt("schema_version"),
            occurredAt = getObject("occurred_at", OffsetDateTime::class.java),
            headers = mapper.readValue(getString("headers"), Map::class.java)
                .entries.associate { it.key.toString() to it.value as Any },
            payload = mapper.readValue(getString("payload"), Map::class.java)
                .entries.associate { it.key.toString() to it.value as Any },
        ),
        getLong("cursor_position"),
    )

    private fun encodeCursor(position: Long): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(position.toString().toByteArray(StandardCharsets.UTF_8))

    private fun decodeCursor(value: String): Cursor = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('|')
        require(decoded.size == 1)
        Cursor(decoded.single().toLong().also { require(it > 0) })
    }.getOrElse { throw IllegalArgumentException("after must be a catalog event cursor", it) }

    private data class Cursor(val position: Long)
    private data class CatalogEventRow(val event: CatalogEvent, val position: Long)

    private companion object {
        const val MAX_PAGE_SIZE = 500
    }
}
