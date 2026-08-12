// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

@ApplicationScoped
class CatalogPackSeeder(
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
    private val catalogJson: CatalogJson,
    private val schemaProfile: CatalogSchemaProfile,
    private val clock: Clock,
) {
    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        PACKS.forEach { pack -> register(pack) }
    }

    @Suppress("MagicNumber")
    private fun register(pack: PackSchema) {
        val document = requireNotNull(javaClass.getResourceAsStream(pack.resource)) {
            "catalog pack resource ${pack.resource} is missing"
        }.use(mapper::readTree)
        schemaProfile.requireValid(document, "urn:catalog-schema:${pack.id}:${pack.version}")
        val hash = catalogJson.sha256(document)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO catalog_schemas """ +
                    """(key, schema_id, schema_version, document, sha256, registered_at) """ +
                    """VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?) """ +
                    """ON CONFLICT (key) DO NOTHING""",
            ).use { statement ->
                statement.setString(1, "${pack.id}:${pack.version}")
                statement.setString(2, pack.id)
                statement.setInt(3, pack.version)
                statement.setString(4, mapper.writeValueAsString(document))
                statement.setString(5, hash)
                statement.setObject(6, java.time.OffsetDateTime.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC))
                statement.executeUpdate()
            }
            connection.prepareStatement("SELECT sha256 FROM catalog_schemas WHERE key = ?").use { statement ->
                statement.setString(1, "${pack.id}:${pack.version}")
                statement.executeQuery().use { rows ->
                    check(rows.next() && rows.getString(1) == hash) {
                        "schema ${pack.id}:${pack.version} is already registered with different content"
                    }
                }
            }
        }
    }

    private data class PackSchema(val id: String, val version: Int, val resource: String)

    private companion object {
        val PACKS = listOf(
            PackSchema("org.openbank.banking.deposit", 1, "/catalog-packs/banking/deposit-v1.schema.json"),
            PackSchema("org.openbank.insurance.term-life", 1, "/catalog-packs/insurance/term-life-v1.schema.json"),
        )
    }
}
