// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.productcatalog.domain.catalog.RevisionApprovalPolicy
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
    @ConfigProperty(name = "openbank.catalog.packs")
    private val enabledPacks: java.util.Optional<String>,
) {
    @Suppress("UnusedParameter")
    fun onStart(@Observes @Priority(Interceptor.Priority.APPLICATION - STARTUP_PRIORITY_OFFSET) event: StartupEvent) {
        val enabled = enabledPacks.orElse("").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        enabled.flatMap(::manifest).forEach { pack -> register(pack) }
    }

    /**
     * A pack is discovered from its own `pack.json`; the kernel holds no list of packs, schemas or
     * policies. An enabled pack without a manifest fails startup rather than registering nothing.
     */
    private fun manifest(pack: String): List<PackSchema> {
        require(PACK_NAME.matches(pack)) { "catalog pack name '$pack' must match ${PACK_NAME.pattern}" }
        val resource = "/catalog-packs/$pack/pack.json"
        val manifest = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "catalog pack '$pack' is enabled but $resource is missing"
        }.use(mapper::readTree)
        require(manifest.path("pack").asText() == pack) {
            "$resource declares pack '${manifest.path("pack").asText()}'"
        }
        return manifest.path("schemas").map { entry ->
            PackSchema(
                pack = pack,
                id = entry.path("id").asText(),
                version = entry.path("version").asInt(),
                resource = "/catalog-packs/$pack/${entry.path("resource").asText()}",
                approvalPolicy = entry.get("approvalPolicy")
                    ?.let { mapper.treeToValue(it, RevisionApprovalPolicy::class.java) },
            )
        }.also { require(it.isNotEmpty()) { "$resource declares no schemas" } }
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
                registerApprovalPolicy(connection, pack)
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

    /**
     * A registered policy is immutable per schema version, like the schema document: a manifest
     * that changes or drops it fails startup. Declaring a policy for a version that had none only
     * tightens governance (open drafts then need the approvals), so that is accepted.
     */
    private fun registerApprovalPolicy(connection: Connection, pack: PackSchema) {
        val stored = connection.prepareStatement(
            "SELECT policy::text FROM catalog_schema_approval_policies WHERE schema_id = ? AND schema_version = ?",
        ).use { statement ->
            statement.bind(pack.id, pack.version)
            statement.executeQuery().use { rows ->
                if (rows.next()) mapper.readValue(rows.getString(1), RevisionApprovalPolicy::class.java) else null
            }
        }
        when {
            stored == pack.approvalPolicy -> Unit
            stored == null && pack.approvalPolicy != null -> connection.prepareStatement(
                "INSERT INTO catalog_schema_approval_policies (schema_id, schema_version, policy, registered_at) " +
                    "VALUES (?, ?, CAST(? AS jsonb), ?)",
            ).use { statement ->
                statement.bind(
                    pack.id,
                    pack.version,
                    mapper.writeValueAsString(pack.approvalPolicy),
                    java.time.OffsetDateTime.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC),
                )
                statement.executeUpdate()
            }
            else -> error(
                "approval policy of schema ${pack.id}:${pack.version} differs from the registered one " +
                    "(registered=$stored, manifest=${pack.approvalPolicy}); policies are immutable per version",
            )
        }
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

    private data class PackSchema(
        val pack: String,
        val id: String,
        val version: Int,
        val resource: String,
        val approvalPolicy: RevisionApprovalPolicy?,
    )

    private companion object {
        const val STARTUP_PRIORITY_OFFSET = 100
        val PACK_NAME = Regex("^[a-z][a-z0-9-]{1,63}$")
    }
}
