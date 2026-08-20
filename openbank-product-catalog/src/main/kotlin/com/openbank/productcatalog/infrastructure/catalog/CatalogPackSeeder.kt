// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class CatalogPackSeeder(
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
    private val catalogJson: CatalogJson,
    private val schemaProfile: CatalogSchemaProfile,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.catalog.packs", defaultValue = "banking,insurance")
    private val enabledPacks: java.util.Optional<String>,
) {
    @Suppress("UnusedParameter")
    fun onStart(@Observes @Priority(Interceptor.Priority.APPLICATION - STARTUP_PRIORITY_OFFSET) event: StartupEvent) {
        val enabled = enabledPacks.orElse("").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        PACKS.filter { it.pack in enabled }.forEach { pack -> register(pack) }
    }

    private fun register(pack: PackSchema) {
        val document = requireNotNull(javaClass.getResourceAsStream(pack.resource)) {
            "catalog pack resource ${pack.resource} is missing"
        }.use(mapper::readTree)
        schemaProfile.requireValid(document, "urn:catalog-schema:${pack.id}:${pack.version}")
        val hash = catalogJson.sha256(document)
        dataSource.connection.use { connection ->
            connection.inTransaction {
                val inserted = insertSchema(connection, pack, document.toString(), hash)
                assertRegisteredHash(connection, pack, hash)
                if (inserted) recordRegistration(connection, pack)
            }
        }
    }

    private fun insertSchema(connection: Connection, pack: PackSchema, document: String, hash: String): Boolean =
        connection.prepareStatement(
            """INSERT INTO catalog_schemas """ +
                """(key, schema_id, schema_version, document, sha256, registered_at) """ +
                """VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?) ON CONFLICT (key) DO NOTHING""",
        ).use { statement ->
            statement.bind(
                "${pack.id}:${pack.version}",
                pack.id,
                pack.version,
                document,
                hash,
                java.time.OffsetDateTime.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC),
            )
            statement.executeUpdate() == 1
        }

    private fun assertRegisteredHash(connection: Connection, pack: PackSchema, expectedHash: String) {
        connection.prepareStatement("SELECT sha256 FROM catalog_schemas WHERE key = ?").use { statement ->
            statement.setString(1, "${pack.id}:${pack.version}")
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == expectedHash) {
                    "schema ${pack.id}:${pack.version} is already registered with different content"
                }
            }
        }
    }

    private fun recordRegistration(connection: java.sql.Connection, pack: PackSchema) {
        val at = Instant.now(clock)
        val aggregateId = UUID.nameUUIDFromBytes("${pack.id}:${pack.version}".toByteArray(StandardCharsets.UTF_8))
        val eventId = Ids.newId()
        val eventType = "com.openbank.catalog.schema_registered"
        val payload = mapper.writeValueAsString(
            mapOf(
                "eventId" to eventId,
                "aggregateType" to "SCHEMA",
                "aggregateId" to aggregateId,
                "eventType" to eventType,
                "schemaVersion" to 1,
                "occurredAt" to at,
                "actorId" to "system:trusted-pack-seeder",
            ),
        )
        connection.prepareStatement(
            """INSERT INTO catalog_audit """ +
                """(id, aggregate_type, aggregate_id, action, actor_id, occurred_at, details) """ +
                """VALUES (?, 'SCHEMA', ?, 'SCHEMA_REGISTERED', 'system:trusted-pack-seeder', ?, CAST(? AS jsonb))""",
        ).use { statement ->
            statement.bind(
                Ids.newId(),
                aggregateId,
                java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
                """{"schemaId":"${pack.id}","version":${pack.version}}""",
            )
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """INSERT INTO catalog_outbox (id, aggregate_type, aggregate_id, event_type, schema_version, """ +
                """occurred_at, headers, created_at, payload) VALUES """ +
                """(?, 'SCHEMA', ?, ?, 1, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb))""",
        ).use { statement ->
            val timestamp = java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC)
            statement.bind(
                eventId,
                aggregateId,
                eventType,
                timestamp,
                mapper.writeValueAsString(
                    mapOf(
                        "ce_specversion" to "1.0",
                        "ce_id" to eventId,
                        "ce_source" to "openbank-product-catalog",
                        "ce_type" to eventType,
                        "content-type" to "application/json",
                    ),
                ),
                timestamp,
                payload,
            )
            statement.executeUpdate()
        }
    }

    private fun Connection.inTransaction(block: () -> Unit) {
        autoCommit = false
        runCatching(block)
            .onSuccess { commit() }
            .onFailure { rollback() }
            .getOrThrow()
    }

    private fun PreparedStatement.bind(vararg values: Any) {
        values.forEachIndexed { index, value -> setObject(index + 1, value) }
    }

    private data class PackSchema(val pack: String, val id: String, val version: Int, val resource: String)

    private companion object {
        const val STARTUP_PRIORITY_OFFSET = 100
        val PACKS = listOf(
            PackSchema("banking", "org.openbank.banking.deposit", 1, "/catalog-packs/banking/deposit-v1.schema.json"),
            PackSchema("banking", "org.openbank.banking.deposit", 2, "/catalog-packs/banking/deposit-v2.schema.json"),
            PackSchema(
                "banking",
                "org.openbank.banking.legacy-product",
                1,
                "/catalog-packs/banking/legacy-product-v1.schema.json",
            ),
            PackSchema("banking", "org.openbank.banking.loan", 1, "/catalog-packs/banking/loan-v1.schema.json"),
            PackSchema("banking", "org.openbank.banking.loan", 2, "/catalog-packs/banking/loan-v2.schema.json"),
            PackSchema(
                "insurance",
                "org.openbank.insurance.term-life",
                1,
                "/catalog-packs/insurance/term-life-v1.schema.json",
            ),
            PackSchema(
                "insurance",
                "org.openbank.insurance.term-life",
                2,
                "/catalog-packs/insurance/term-life-v2.schema.json",
            ),
        )
    }
}
